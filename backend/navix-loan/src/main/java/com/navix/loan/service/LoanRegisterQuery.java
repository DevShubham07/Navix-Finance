package com.navix.loan.service;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The loan register as SQL: one lightweight row per loan carrying only what the register needs to
 * <b>filter, sort, segment and count</b>. {@link LoanRegisterService#page} pages over it and enriches
 * just the ids on the page.
 *
 * <p><b>What is here and what is not.</b> Only columns that are stored on {@code loan},
 * {@code loan_application} or {@code customer_profile}, plus three that are pure functions of stored
 * columns and the day: {@code status} (the SQL twin of {@link com.navix.loan.entity.Loan#effectiveStatus}),
 * {@code dpd} (twin of {@code LoanMath.daysPastDue}, measured to the close date when there is one) and
 * {@code loan_cycle} (a window function over stored columns). <b>Outstanding is deliberately absent</b>:
 * it is compute-on-read money ({@code RepaymentService.outstandingAsOf}) and re-deriving it here would
 * be a second source of truth for what a borrower owes. The collections officer lives in
 * navix-collections, which this module may not depend on. Both are filtered and sorted in Java by
 * the service, over the ids this query returns.
 *
 * <p>Keep the {@code status} CASE in step with {@code Loan.effectiveStatus}, and the segment clauses
 * in step with {@code segmentOf} in the frontend's {@code loans/segments.ts}.
 */
@Component
public class LoanRegisterQuery {

    /** One matching loan: its id and its 1-based cycle (which only the window over ALL loans can give). */
    public record Hit(long loanId, int loanCycle) {
    }

    /**
     * @param needle free-text search (already trimmed/lower-cased by the caller); null = none
     * @param from disbursed-on window start, inclusive; null = open
     * @param to disbursed-on window end, inclusive; null = open
     * @param segment {@code active|overdue|closed}; null/blank/"all" = no restriction
     * @param today the day DPD and the effective status are measured against — bound, never
     *     {@code current_date}, so Java and SQL cannot disagree across midnight
     */
    public record Filter(String needle, LocalDate from, LocalDate to, String segment, LocalDate today) {
    }

    /**
     * The date window sits INSIDE the CTE on purpose: {@code loan_cycle} is "which advance is this
     * among the loans in the window" (that is what the old Java pass computed over the window's
     * loans), so the window must narrow the rows BEFORE row_number() and every other filter after it.
     *
     * <p>The IST midnight casts ({@code *_ts}) let a DAY filter compare like with like: SqlFilter
     * binds IST-day bounds as instants, and a bare {@code date} column would be compared to them in
     * whatever zone the session happens to run in. {@code distinct on} (not a join) picks one
     * application per loan and one profile per application, so a duplicate can never fan a loan out
     * into two register rows.
     */
    private static final String CTE_HEAD = """
            with reg as (
                select l.id as loan_id,
                       l.customer_id,
                       p.full_name, p.pan, p.mobile,
                       l.principal, l.net_disbursed, l.total_repayable,
                       l.disbursed_on, l.due_date, l.closed_on, a.sanctioned_at,
                       cast(l.disbursed_on as timestamp) at time zone 'Asia/Kolkata' as disbursed_ts,
                       cast(l.due_date as timestamp) at time zone 'Asia/Kolkata' as due_ts,
                       cast(l.closed_on as timestamp) at time zone 'Asia/Kolkata' as closed_ts,
                       case when l.status = 'ACTIVE' and l.due_date is not null and l.due_date < :today
                            then 'OVERDUE' else l.status end as status,
                       greatest(0, coalesce(l.closed_on, :today) - l.due_date) as dpd,
                       row_number() over (partition by l.customer_id
                                          order by l.disbursed_on asc nulls last, l.id) as loan_cycle
                from loan l
                left join (select distinct on (loan_id) loan_id, id, sanctioned_at
                           from loan_application where loan_id is not null
                           order by loan_id, id) a on a.loan_id = l.id
                left join (select distinct on (application_id) application_id, full_name, pan, mobile
                           from customer_profile
                           order by application_id, id) p on p.application_id = a.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LoanRegisterQuery(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * The columns the register can sort on in SQL, each a fixed expression — the request only picks a
     * key, so nothing user-supplied reaches the ORDER BY. {@code outstandingPaise} and
     * {@code assignedOfficerName} are not here: the service sorts those in Java.
     */
    static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("dueDate", "r.due_date"),
            Map.entry("sanctionedAt", "r.sanctioned_at"),
            Map.entry("disbursedOn", "r.disbursed_on"),
            Map.entry("closedOn", "r.closed_on"),
            Map.entry("principalPaise", "r.principal"),
            Map.entry("netDisbursedPaise", "r.net_disbursed"),
            Map.entry("totalRepayablePaise", "r.total_repayable"),
            Map.entry("dpd", "r.dpd"),
            Map.entry("borrowerName", "lower(r.full_name)"),
            Map.entry("loanCycle", "r.loan_cycle"),
            Map.entry("status", "r.status"));

    private static FilterColumns.Builder sqlColumns() {
        return FilterColumns.builder()
                // TEXT only: name is identity, so there is never a dropdown of borrower names. PAN and
                // mobile are searchable through q but not filterable here, as on the old page.
                .column("borrowerName", "r.full_name", TEXT)
                .column("sanctionedAt", "r.sanctioned_at", DAY)
                .column("disbursedOn", "r.disbursed_ts", DAY)
                .column("dueDate", "r.due_ts", DAY)
                .column("closedOn", "r.closed_ts", DAY)
                .column("loanCycle", "r.loan_cycle", NUM)
                // Money is paise in the column and rupees on screen: scale on the bound operand, so
                // the column is compared bare.
                .scaled("principalPaise", "r.principal", 100, NUM)
                .scaled("netDisbursedPaise", "r.net_disbursed", 100, NUM)
                .scaled("totalRepayablePaise", "r.total_repayable", 100, NUM)
                .column("dpd", "r.dpd", NUM)
                // The effective status, so a checklist offers OVERDUE (which is never a stored value
                // until collections flips it). TEXT is the "too many values" fallback only.
                .column("status", "r.status", IN, TEXT);
    }

    /** What reaches SQL. */
    public static final FilterColumns FILTER_COLUMNS = sqlColumns().build();

    /**
     * What the request is PARSED against: the SQL columns plus the two the service filters in Java.
     * Their SQL is a placeholder that is never emitted — the service strips both keys
     * ({@link LoanRegisterService#SQL_SPEC}) before a spec reaches {@link SqlFilter}.
     */
    static final FilterColumns PARSE_COLUMNS = sqlColumns()
            .scaled("outstandingPaise", "0", 100, NUM)
            .column("assignedOfficerName", "0", IN)
            .build();

    /** Matching loans for one page, in display order. */
    public List<Hit> pageHits(Filter filter, FilterSpec spec, String sort, String dir, int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = select(filter, spec, sort, dir, params) + " limit :limit offset :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> new Hit(rs.getLong("loan_id"), rs.getInt("loan_cycle")));
    }

    /** Every matching loan, in display order — the input to the Java-side filters and sorts. */
    public List<Hit> allHits(Filter filter, FilterSpec spec, String sort, String dir) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return jdbc.query(select(filter, spec, sort, dir, params), params,
                (rs, i) -> new Hit(rs.getLong("loan_id"), rs.getInt("loan_cycle")));
    }

    /** The row count under exactly the clauses {@link #pageHits} uses, so total and page cannot disagree. */
    public long count(Filter filter, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = cte(filter, params) + "select count(*) from reg r" + where(filter, spec, true, params);
        Long n = jdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * The distinct values of one filterable column. It runs inside the SAME window and search the
     * list uses (one {@link #baseClauses}), plus the segment chip: a value no row in the chip can have
     * is not a choice worth offering. The other columns' filters narrow it too; the column's own is
     * dropped by {@link SqlFacets}. The facet is only reachable for a column registered {@code IN}.
     */
    public SqlFacets.Facet facet(Filter filter, FilterSpec spec, String column) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> base = baseClauses(filter, true, params);
        return SqlFacets.facet(jdbc, cte(filter, params), "reg r", base.isEmpty() ? null : String.join(" and ", base),
                spec, FILTER_COLUMNS, column, params);
    }

    private static String select(Filter filter, FilterSpec spec, String sort, String dir, MapSqlParameterSource params) {
        String column = SORTS.getOrDefault(sort == null ? "dueDate" : sort, SORTS.get("dueDate"));
        String direction = "asc".equalsIgnoreCase(dir) ? "asc" : "desc";
        return cte(filter, params) + "select r.loan_id, r.loan_cycle from reg r"
                + where(filter, spec, true, params)
                + " order by " + column + " " + direction + " nulls last, r.loan_id " + direction;
    }

    /** The CTE with its window bound; also binds {@code today}, which the CTE and the segment both read. */
    private static String cte(Filter f, MapSqlParameterSource p) {
        p.addValue("today", java.sql.Date.valueOf(f.today()));
        StringBuilder sb = new StringBuilder(CTE_HEAD);
        List<String> window = new ArrayList<>();
        if (f.from() != null) {
            p.addValue("from", java.sql.Date.valueOf(f.from()));
            window.add("l.disbursed_on >= :from");
        }
        if (f.to() != null) {
            p.addValue("to", java.sql.Date.valueOf(f.to()));
            window.add("l.disbursed_on <= :to");
        }
        if (!window.isEmpty()) {
            sb.append("    where ").append(String.join(" and ", window)).append('\n');
        }
        return sb.append(")\n").toString();
    }

    /** Search and segment first, the column filters ANDed last — a filter can only narrow. */
    private static String where(Filter f, FilterSpec spec, boolean applySegment, MapSqlParameterSource p) {
        List<String> base = baseClauses(f, applySegment, p);
        String filters = SqlFilter.and(spec, FILTER_COLUMNS, p);
        if (base.isEmpty()) {
            return filters.isEmpty() ? "" : " where true" + filters;
        }
        return " where " + String.join(" and ", base) + filters;
    }

    private static List<String> baseClauses(Filter f, boolean applySegment, MapSqlParameterSource p) {
        List<String> clauses = new ArrayList<>();
        if (f.needle() != null && !f.needle().isEmpty()) {
            // The old Java match: name / PAN lower-cased, mobile and loan id as typed.
            p.addValue("needle", "%" + escapeLike(f.needle()) + "%");
            clauses.add("(lower(r.full_name) like :needle escape '\\'"
                    + " or lower(r.pan) like :needle escape '\\'"
                    + " or r.mobile like :needle escape '\\'"
                    + " or cast(r.loan_id as text) like :needle escape '\\')");
        }
        if (applySegment && f.segment() != null) {
            // Mirrors segmentOf(): anything not recognisably overdue or closed is live.
            switch (f.segment()) {
                case "overdue" -> clauses.add("r.status in ('OVERDUE', 'IN_COLLECTIONS')");
                case "closed" -> clauses.add("r.status in ('REPAID', 'CLOSED')");
                case "active" -> clauses.add("r.status not in ('OVERDUE', 'IN_COLLECTIONS', 'REPAID', 'CLOSED')");
                default -> { }
            }
        }
        return clauses;
    }
}
