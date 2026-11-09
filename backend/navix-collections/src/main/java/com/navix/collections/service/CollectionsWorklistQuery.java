package com.navix.collections.service;

import static com.navix.common.query.ColumnFilter.Kind.DAY;
import static com.navix.common.query.ColumnFilter.Kind.IN;
import static com.navix.common.query.ColumnFilter.Kind.NUM;
import static com.navix.common.query.ColumnFilter.Kind.TEXT;
import static com.navix.common.util.LikeEscape.escapeLike;

import com.navix.common.query.FilterColumns;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.common.query.SqlFilter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The collections worklist as SQL: one lightweight row per live loan carrying exactly what the DPD
 * register needs to <b>filter, sort and page</b> on its STORED columns. {@link CollectionsService}
 * pages over it and enriches only the ids on the page.
 *
 * <p>What is deliberately NOT here: anything derived. {@code outstanding} is compute-on-read
 * ({@code RepaymentService.outstandingAsOf}); re-deriving a borrower's balance in SQL would be a second
 * source of truth for what they owe. The credit/disbursing names come out of the event trail the
 * same way. Those columns are filtered and sorted in Java over the ids this query returns
 * ({@link CollectionsService#worklistPage}). {@code due_date} IS stored, so the DPD day count and
 * bucket are exact here (they are the register's main axis) — the {@code dpd}/{@code bucket} CASE
 * mirrors {@link DpdCalculator}; keep the two in step.
 *
 * <p>The set of loans mirrors {@code LoanDirectoryAdapter.listCollectible}: ACTIVE / OVERDUE /
 * IN_COLLECTIONS, due on or before {@code asOf + PRE_DUE_WINDOW_DAYS}.
 */
@Component
public class CollectionsWorklistQuery {

    /**
     * @param needle search over customer id, name, mobile, PAN, employer, officer name, loan id
     * @param from inclusive due-date window start (null = open)
     * @param to inclusive due-date window end (null = open)
     * @param bucket a {@code DpdBucket} name, or null/"ALL" for every bucket
     * @param scoped true for a COLLECTION_EXECUTIVE: only cases assigned to {@code officerId}
     * @param officerId the executive's staff id; null while {@code scoped} sees nothing (fail closed)
     * @param asOf horizon anchor for the collectible set
     * @param today the day DPD is measured against (IST)
     */
    public record WorklistFilter(
            String needle, LocalDate from, LocalDate to, String bucket,
            boolean scoped, Long officerId, LocalDate asOf, LocalDate today) {
    }

    private static final String WORKLIST_CTE = """
            with wl as (
                select l.id as loan_id, l.customer_id, l.due_date, l.principal,
                       cast(l.due_date as timestamp) at time zone 'Asia/Kolkata' as due_ts,
                       coalesce(greatest(0, cast(:today as date) - l.due_date), 0) as dpd,
                       case
                         when coalesce(cast(:today as date) - l.due_date, 0) <= 0 then 'UPCOMING'
                         when cast(:today as date) - l.due_date <= 7 then 'T0_T7'
                         when cast(:today as date) - l.due_date <= 30 then 'T8_T30'
                         when cast(:today as date) - l.due_date <= 60 then 'T30_T60'
                         when cast(:today as date) - l.due_date <= 90 then 'T60_T90'
                         else 'T90_PLUS'
                       end as bucket,
                       p.full_name, p.pan, p.mobile, p.employer, p.monthly_salary_paise as salary,
                       cc.assigned_officer_id as officer_id, su.name as officer_name
                from loan l
                left join (select distinct on (a.loan_id) a.loan_id, a.id as app_id
                           from loan_application a where a.loan_id is not null
                           order by a.loan_id, a.id) la on la.loan_id = l.id
                left join customer_profile p on p.application_id = la.app_id
                left join (select distinct on (c.loan_id) c.loan_id, c.assigned_officer_id
                           from collection_case c order by c.loan_id, c.created_at, c.id) cc on cc.loan_id = l.id
                left join staff_user su on su.id = cc.assigned_officer_id
                where l.status in ('ACTIVE', 'OVERDUE', 'IN_COLLECTIONS')
                  and l.due_date <= cast(:horizon as date)
            )
            """;

    /**
     * Sortable columns, each a fixed SQL expression — the request only picks a key. {@code bucketLabel}
     * sorts by severity (days past due), which is what the label means, not the alphabet. The Java-side
     * sorts ({@link CollectionsService}) are not here.
     */
    static final Map<String, String> SORTS = Map.of(
            "customerId", "w.customer_id",
            "borrowerName", "lower(w.full_name)",
            "loanId", "w.loan_id",
            "principalPaise", "w.principal",
            "dueDate", "w.due_date",
            "dpd", "w.dpd",
            "bucketLabel", "w.dpd",
            "employer", "lower(w.employer)",
            "salaryPaise", "w.salary",
            "officerName", "lower(w.officer_name)");

    /**
     * Filterable columns, each a SQL literal over the {@code wl} CTE. The request only picks a key.
     * Money is stored in paise and the client types rupees, so those two declare a display scale of
     * 100 (the operand is scaled; the column stays bare).
     *
     * <p>The officer id IS the scope column ({@link WorklistFilter#officerId()} restricts on it), and it is
     * registered deliberately: {@link #where} ANDs the column filters AFTER the scope clause, so a filter
     * on it can only narrow what the caller may already see — an executive filtering on another officer
     * gets nothing, never that officer's rows. Do not "fix" this by removing it; keying the Officer
     * checklist on the NAME instead would merge two officers who share a display name. An unassigned
     * case is {@code __NULL__} (the client labels it "Unallocated").
     *
     * Identity columns (customer id, loan id, mobile, PAN) are not registered, so they cannot be
     * faceted into a dropdown of borrowers; the search box is how you find one.
     */
    public static final FilterColumns FILTER_COLUMNS = FilterColumns.builder()
            .column("borrowerName", "w.full_name", TEXT)
            .column("employer", "w.employer", TEXT)
            .scaled("principalPaise", "w.principal", 100, NUM)
            .scaled("salaryPaise", "w.salary", 100, NUM)
            .column("dueDate", "w.due_ts", DAY)
            .column("dpd", "w.dpd", NUM)
            .column("bucketLabel", "w.bucket", IN, TEXT)
            .column("officerName", "w.officer_id", IN)
            .build();

    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public CollectionsWorklistQuery(JdbcTemplate jdbcTemplate) {
        this(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    /** For tests, which hand in a recording template. */
    CollectionsWorklistQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Loan ids for one page, in display order. {@code sort} null/unknown falls back to most overdue first. */
    public List<Long> pageIds(WorklistFilter filter, FilterSpec spec, int offset, int limit, String sort, String dir) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.queryForList(WORKLIST_CTE + "select w.loan_id from wl w" + where
                + orderBy(sort, dir) + " limit :limit offset :offset", params, Long.class);
    }

    /** Every matching loan id in display order — the Java-side filters/sorts need the whole set. */
    public List<Long> allIds(WorklistFilter filter, FilterSpec spec, String sort, String dir) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        return jdbc.queryForList(WORKLIST_CTE + "select w.loan_id from wl w" + where + orderBy(sort, dir),
                params, Long.class);
    }

    /** The row count under exactly the clauses {@link #pageIds} uses, so total and page cannot disagree. */
    public long count(WorklistFilter filter, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        Long n = jdbc.queryForObject(WORKLIST_CTE + "select count(*) from wl w" + where, params, Long.class);
        return n == null ? 0 : n;
    }

    /** Every in-scope loan id with its DPD bucket — what the bucket cards aggregate over. No view clauses. */
    public Map<Long, String> bucketsInScope(WorklistFilter scopeOnly) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = " where " + String.join(" and ", baseClauses(scopeOnly, false, params));
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        jdbc.query(WORKLIST_CTE + "select w.loan_id, w.bucket from wl w" + where, params,
                rs -> {
                    out.put(rs.getLong("loan_id"), rs.getString("bucket"));
                });
        return out;
    }

    /**
     * The distinct values of one filterable column, for its checklist — INSIDE the same scope, search,
     * window and bucket the list uses (one {@link #baseClauses}): a bare {@code select distinct} would
     * offer a scoped executive values from cases that are not theirs. The other columns' filters
     * narrow it too; the column's own is dropped by {@link SqlFacets}. Only {@code IN} columns facet,
     * so a name/PAN/mobile/loan id can never become a dropdown.
     */
    public SqlFacets.Facet facet(WorklistFilter filter, FilterSpec spec, String column) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> base = baseClauses(filter, true, params);
        return SqlFacets.facet(jdbc, WORKLIST_CTE, "wl w", String.join(" and ", base), spec, FILTER_COLUMNS,
                column, params);
    }

    private static String orderBy(String sort, String dir) {
        String column = SORTS.getOrDefault(sort == null ? "dpd" : sort, SORTS.get("dpd"));
        // Unsorted default and a bad key both land on "most overdue first".
        String direction = sort == null || !SORTS.containsKey(sort) ? "desc" : "asc".equalsIgnoreCase(dir) ? "asc" : "desc";
        return " order by " + column + " " + direction + " nulls last, w.loan_id " + direction;
    }

    /** Scope / search / window / bucket first, the column filters ANDed last so they can only narrow. */
    private static String where(WorklistFilter f, FilterSpec spec, MapSqlParameterSource p) {
        List<String> base = baseClauses(f, true, p);
        return " where " + String.join(" and ", base) + SqlFilter.and(spec, FILTER_COLUMNS, p);
    }

    /** Never empty: the scope clause (or a constant true) is always first. */
    private static List<String> baseClauses(WorklistFilter f, boolean applyViewClauses, MapSqlParameterSource p) {
        p.addValue("today", java.sql.Date.valueOf(f.today()));
        p.addValue("horizon", java.sql.Date.valueOf(f.asOf().plusDays(com.navix.common.loan.LoanDirectory.PRE_DUE_WINDOW_DAYS)));
        List<String> clauses = new ArrayList<>();

        // Scope first. An executive whose staff id does not resolve sees NOTHING, not everything: fail
        // closed. (The old whole-list path showed the whole book in that case — a scoping hole, not a feature.)
        if (f.scoped()) {
            if (f.officerId() == null) {
                clauses.add("false");
            } else {
                p.addValue("scopeOfficer", f.officerId());
                clauses.add("w.officer_id = :scopeOfficer");
            }
        } else {
            clauses.add("true");
        }
        if (!applyViewClauses) {
            return clauses;
        }
        if (f.needle() != null && !f.needle().isBlank()) {
            p.addValue("needle", "%" + escapeLike(f.needle().strip().toLowerCase(java.util.Locale.ROOT)) + "%");
            clauses.add("(lower(w.full_name) like :needle escape '\\'"
                    + " or lower(w.pan) like :needle escape '\\'"
                    + " or w.mobile like :needle escape '\\'"
                    + " or lower(w.employer) like :needle escape '\\'"
                    + " or lower(w.officer_name) like :needle escape '\\'"
                    + " or cast(w.customer_id as text) like :needle escape '\\'"
                    + " or cast(w.loan_id as text) like :needle escape '\\')");
        }
        if (f.from() != null) {
            p.addValue("from", java.sql.Date.valueOf(f.from()));
            clauses.add("w.due_date >= :from");
        }
        if (f.to() != null) {
            p.addValue("to", java.sql.Date.valueOf(f.to()));
            clauses.add("w.due_date <= :to");
        }
        if (f.bucket() != null && !f.bucket().isBlank() && !"ALL".equals(f.bucket())) {
            p.addValue("bucket", f.bucket());
            clauses.add("w.bucket = :bucket");
        }
        return clauses;
    }
}
