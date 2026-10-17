package com.navix.loan.service;

import static com.navix.common.query.ColumnFilter.Kind.DAY;
import static com.navix.common.query.ColumnFilter.Kind.IN;
import static com.navix.common.query.ColumnFilter.Kind.TEXT;
import static com.navix.common.util.LikeEscape.escapeLike;

import com.navix.common.query.FilterColumns;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.common.query.SqlFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The "customer book" as SQL: one lightweight row per customer carrying exactly what the Customers
 * list needs to <b>filter, sort, segment and count</b> — never the full {@code CustomerSummary}.
 * {@link CustomerService#page} pages over it and hydrates only the ids on the page;
 * {@link CustomerService#summary} groups over it for the segment chips.
 *
 * <p>The {@code segment} CASE mirrors {@code segmentOf()} in the frontend's {@code segments.ts}
 * (loan status outranks application status) and {@code loan_status} mirrors
 * {@code Loan.effectiveStatus(today)}; the {@code stage} CTE is the SQL twin of
 * {@code ApplicationEventRepository.findCurrentStatusEnteredAt}. Keep the three in step.
 */
@Component
public class CustomerBookQuery {

    /**
     * @param scopeIds customers a scoped staffer may see (null = whole book)
     * @param scopeIncludesUnallocated true when the caller may also see customers nobody owns
     * @param mineIds "My customers" restriction — owned by or decided by the caller (null = off)
     */
    public record BookFilter(
            String needle,
            Instant from,
            Instant to,
            String segment,
            Set<Long> scopeIds,
            boolean scopeIncludesUnallocated,
            Set<Long> mineIds,
            LocalDate today) {
    }

    public record SegmentCount(String segment, long count, long unallocated, long reloan, long blocked) {
        public SegmentCount(String segment, long count, long unallocated) {
            this(segment, count, unallocated, 0, 0);
        }

        public SegmentCount(String segment, long count, long unallocated, long reloan) {
            this(segment, count, unallocated, reloan, 0);
        }
    }

    public static final Set<String> SEGMENTS = Set.of(
            "incomplete", "pending", "review", "approved", "disbursementPending",
            "active", "overdue", "hold", "rejected", "closed", "unallocated", "reloan", "blocked");

    private static final String BOOK_CTE = """
            with latest_app as (
                select distinct on (a.customer_id) a.customer_id, a.id as app_id, a.status as app_status, a.created_at
                from loan_application a
                order by a.customer_id, a.id desc
            ), latest_loan as (
                select distinct on (l.customer_id) l.customer_id,
                       case when l.status = 'ACTIVE' and l.due_date is not null and l.due_date < :today
                            then 'OVERDUE' else l.status end as loan_status
                from loan l
                order by l.customer_id, l.id desc
            ), prof as (
                select distinct on (a.customer_id) a.customer_id, p.full_name, p.pan, p.mobile
                from customer_profile p
                join loan_application a on a.id = p.application_id
                order by a.customer_id, a.id desc
            ), stage as (
                select e.application_id, max(e.at) as at
                from application_event e
                join loan_application a on a.id = e.application_id
                where e.to_status = a.status
                  and (e.from_status is null or e.from_status <> e.to_status)
                group by e.application_id
            ), book as (
                select la.customer_id,
                       la.created_at,
                       o.owner_staff_id,
                       (la.app_status <> 'DRAFT' and exists (
                            select 1 from loan cl
                            where cl.customer_id = la.customer_id and cl.status = 'CLOSED'
                              and cl.closed_on <= cast(la.created_at at time zone 'Asia/Kolkata' as date))) as reloan,
                       exists (
                            select 1 from application_rejection r
                            where r.mobile = pr.mobile and r.blocked_until > now()) as blocked,
                       coalesce(s.at, la.created_at) as status_changed_at,
                       pr.full_name, pr.pan, pr.mobile,
                       case
                         when ll.loan_status in ('OVERDUE', 'IN_COLLECTIONS') then 'overdue'
                         when la.app_status in ('OVERDUE', 'DEFAULTED') then 'overdue'
                         when ll.loan_status in ('ACTIVE', 'DISBURSING') then 'active'
                         when la.app_status in ('DISBURSED', 'ACTIVE') then 'active'
                         when la.app_status in ('REVIEW_PENDING', 'CREDIT_EXEC_PENDING', 'CREDIT_HEAD_PENDING') then 'review'
                         when la.app_status in ('KYC_APPROVED', 'CREDIT_EXEC_APPROVED', 'CREDIT_HEAD_APPROVED', 'SANCTIONED') then 'approved'
                         when la.app_status in ('DISBURSEMENT_PENDING', 'ACCOUNTANT_PENDING') then 'disbursementPending'
                         when la.app_status = 'DISBURSEMENT_FAILED' then 'hold'
                         when la.app_status = 'DRAFT' then 'incomplete'
                         when la.app_status in ('KYC_REJECTED', 'REJECTED') then 'rejected'
                         when la.app_status in ('CLOSED', 'WRITTEN_OFF', 'CANCELLED') then 'closed'
                         else 'pending'
                       end as segment
                from latest_app la
                left join latest_loan ll on ll.customer_id = la.customer_id
                left join customer_owner o on o.customer_id = la.customer_id
                left join stage s on s.application_id = la.app_id
                left join prof pr on pr.customer_id = la.customer_id
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerBookQuery(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** Customer ids for one page, in display order (stage date desc, nulls last). */
    public List<Long> pageIds(BookFilter filter, int offset, int limit) {
        return pageIds(filter, offset, limit, null, null);
    }

    /**
     * The columns the register can sort on, each mapped to a fixed SQL expression — the request
     * only ever picks a key from this map, so nothing user-supplied reaches the ORDER BY.
     * {@code stage} follows the lifecycle (incomplete → … → closed), not the alphabet.
     */
    static final java.util.Map<String, String> SORTS = java.util.Map.of(
            "stageDate", "b.status_changed_at",
            "date", "b.created_at",
            "name", "lower(b.full_name)",
            "customerId", "b.customer_id",
            "mobile", "b.mobile",
            "pan", "lower(b.pan)",
            "stage", "case b.segment when 'incomplete' then 1 when 'pending' then 2 when 'review' then 3"
                    + " when 'approved' then 4 when 'disbursementPending' then 5 when 'hold' then 6"
                    + " when 'active' then 7 when 'overdue' then 8 when 'closed' then 9 when 'rejected' then 10"
                    + " else 11 end");

    /**
     * The columns a staffer can filter on, each tied to a SQL expression written here as a literal —
     * the request only ever picks a key (same idea as {@link #SORTS}, which this mirrors), so nothing
     * user-supplied reaches the statement. Every expression is over the {@code book} CTE, so a column
     * the CTE does not carry (account, IFSC, amount, owner NAME …) is not filterable until the CTE
     * grows it; and none of them is money, so there is no display scale to declare.
     *
     * <p>No scope column is registered, deliberately: {@code owner_staff_id} and {@code customer_id}
     * are what {@link BookFilter#scopeIds()} / {@code mineIds()} restrict on, and a filter on either
     * would let a request argue with its own scope. Filters are ANDed after scope, so they can only
     * narrow anyway; keeping them out of the registry means they cannot even try.
     *
     * <p>Text columns match the SQL value, the client matches the cell: {@code full_name}, {@code pan}
     * and {@code mobile} are shown as stored, so the two agree. {@code stage} is a checklist of the
     * segment KEYS (the client maps them to the words on screen). Its TEXT kind exists only for the
     * client's "too many values, degrade to text" path, which a 11-value column can never reach.
     * Identity columns are never faceted — a name/PAN/mobile column is TEXT only, so there is no
     * dropdown of ~9,700 of them.
     */
    public static final FilterColumns FILTER_COLUMNS = FilterColumns.builder()
            .column("name", "b.full_name", TEXT)
            .column("mobile", "b.mobile", TEXT)
            .column("pan", "b.pan", TEXT)
            .column("date", "b.created_at", DAY)
            .column("stageDate", "b.status_changed_at", DAY)
            .column("stage", "b.segment", IN, TEXT)
            .build();

    /** The stage column is the chip row's own dimension: see {@link #segmentCounts(BookFilter, FilterSpec)}. */
    private static final String STAGE_COLUMN = "stage";

    /** Customer ids for one page, sorted by a {@link #SORTS} key ({@code null} = stage date) and direction. */
    public List<Long> pageIds(BookFilter filter, int offset, int limit, String sort, String dir) {
        return pageIds(filter, offset, limit, sort, dir, FilterSpec.empty());
    }

    /** As above, further narrowed by the column filters in {@code spec} (AND, after scope and segment). */
    public List<Long> pageIds(BookFilter filter, int offset, int limit, String sort, String dir, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, true, spec, params);
        params.addValue("limit", limit).addValue("offset", offset);
        String column = SORTS.getOrDefault(sort == null ? "stageDate" : sort, SORTS.get("stageDate"));
        String direction = "asc".equalsIgnoreCase(dir) ? "asc" : "desc";
        String sql = BOOK_CTE + "select b.customer_id from book b" + where
                + " order by " + column + " " + direction + " nulls last, b.customer_id " + direction
                + " limit :limit offset :offset";
        return jdbc.queryForList(sql, params, Long.class);
    }

    public long count(BookFilter filter) {
        return count(filter, FilterSpec.empty());
    }

    /** The row count under exactly the clauses {@link #pageIds} uses, so total and page cannot disagree. */
    public long count(BookFilter filter, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, true, spec, params);
        Long n = jdbc.queryForObject(BOOK_CTE + "select count(*) from book b" + where, params, Long.class);
        return n == null ? 0 : n;
    }

    /** Per-segment counts under the filter, ignoring {@code filter.segment()}. */
    public List<SegmentCount> segmentCounts(BookFilter filter) {
        return segmentCounts(filter, FilterSpec.empty());
    }

    /**
     * As above, and the chips follow the column filters the way they follow search and the date
     * window — except {@code stage}. The chips ARE the stage dimension (they already ignore the
     * {@code seg} chip so they do not collapse as you use them), and a stage filter applied here
     * would zero every other chip for the same reason.
     */
    public List<SegmentCount> segmentCounts(BookFilter filter, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, false, spec.without(STAGE_COLUMN), params);
        String sql = BOOK_CTE + "select b.segment, count(*) as n,"
                + " count(*) filter (where b.owner_staff_id is null) as unallocated,"
                + " count(*) filter (where b.reloan) as reloan,"
                + " count(*) filter (where b.blocked) as blocked"
                + " from book b" + where + " group by b.segment";
        return jdbc.query(sql, params, (rs, i) ->
                new SegmentCount(rs.getString("segment"), rs.getLong("n"), rs.getLong("unallocated"),
                        rs.getLong("reloan"), rs.getLong("blocked")));
    }

    /**
     * The distinct values of one filterable column, for its checklist. It runs inside the SAME scope,
     * "mine", search and date clauses the list uses (one {@link #baseClauses}, so the two cannot
     * drift): a bare {@code select distinct} would hand a scoped staffer values from customers
     * outside their own book. The other columns' filters narrow it too; the column's own is dropped
     * by {@link SqlFacets}. The segment chip is left out for the reason {@link #segmentCounts} leaves
     * it out.
     */
    public SqlFacets.Facet facet(BookFilter filter, FilterSpec spec, String column) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> base = baseClauses(filter, false, params);
        return SqlFacets.facet(jdbc, BOOK_CTE, "book b", base.isEmpty() ? null : String.join(" and ", base),
                spec, FILTER_COLUMNS, column, params);
    }

    /**
     * The WHERE: the existing search / window / segment / scope / mine clauses first, the column
     * filters ANDed last — so a filter can only ever narrow what scope already allows.
     */
    private static String where(BookFilter f, boolean applySegment, FilterSpec spec, MapSqlParameterSource p) {
        List<String> base = baseClauses(f, applySegment, p);
        String filters = SqlFilter.and(spec, FILTER_COLUMNS, p);
        if (base.isEmpty()) {
            return filters.isEmpty() ? "" : " where true" + filters;
        }
        return " where " + String.join(" and ", base) + filters;
    }

    private static List<String> baseClauses(BookFilter f, boolean applySegment, MapSqlParameterSource p) {
        p.addValue("today", java.sql.Date.valueOf(f.today()));
        List<String> clauses = new ArrayList<>();

        if (f.needle() != null && !f.needle().isEmpty()) {
            p.addValue("needle", "%" + escapeLike(f.needle()) + "%");
            clauses.add("(lower(b.full_name) like :needle escape '\\'"
                    + " or lower(b.pan) like :needle escape '\\'"
                    + " or b.mobile like :needle escape '\\'"
                    + " or cast(b.customer_id as text) like :needle escape '\\'"
                    + " or exists (select 1 from loan_application a2 where a2.customer_id = b.customer_id"
                    + " and cast(a2.id as text) like :needle escape '\\'))");
        }
        if (f.from() != null) {
            p.addValue("from", Timestamp.from(f.from()));
            clauses.add("b.created_at >= :from");
        }
        if (f.to() != null) {
            p.addValue("to", Timestamp.from(f.to()));
            clauses.add("b.created_at < :to");
        }
        if (applySegment && f.segment() != null && !f.segment().isBlank() && !"all".equals(f.segment())) {
            if ("unallocated".equals(f.segment())) {
                clauses.add("b.owner_staff_id is null");
            } else if ("reloan".equals(f.segment())) {
                clauses.add("b.reloan");
            } else if ("blocked".equals(f.segment())) {
                clauses.add("b.blocked");
            } else {
                p.addValue("seg", f.segment());
                clauses.add("b.segment = :seg");
            }
        }
        if (f.scopeIds() != null) {
            String owned = idsClause(f.scopeIds(), "scopeIds", p);
            clauses.add(f.scopeIncludesUnallocated()
                    ? "(" + owned + " or b.owner_staff_id is null)"
                    : owned);
        }
        if (f.mineIds() != null) {
            clauses.add(idsClause(f.mineIds(), "mineIds", p));
        }
        return clauses;
    }

    /** {@code in ()} is invalid SQL, so an empty set becomes a constant false. */
    private static String idsClause(Set<Long> ids, String name, MapSqlParameterSource p) {
        if (ids.isEmpty()) {
            return "false";
        }
        p.addValue(name, ids);
        return "b.customer_id in (:" + name + ")";
    }

}
