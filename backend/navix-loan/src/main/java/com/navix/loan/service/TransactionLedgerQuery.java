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
import com.navix.loan.dto.LoanDtos.TransactionView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The accountant's transactions ledger as ONE statement. A ledger row is either a disbursal (a
 * {@code loan}) or a repayment (a {@code payment}), so the {@code ledger} CTE is a {@code UNION ALL}
 * of the two selects projecting the same columns, a literal {@code direction} saying which half a
 * row came from. Search, period, direction, column filters, ordering and paging then run once over
 * the union — a filtered page no longer means reading every disbursal and every repayment of the
 * period through two repositories and merging them in Java ({@link TransactionService} used to).
 *
 * <p>Same shape as {@link CustomerBookQuery}: a {@link #SORTS} map of constant expressions, a
 * {@link #FILTER_COLUMNS} registry of SQL written here, every operand bound, and the column filters
 * ANDed after the base clauses so they only narrow. Count, the summary-card sums and the page all
 * take their WHERE from one {@link #where}, so none of them can disagree about what "the filter" is.
 *
 * <p>Indexes this wants (not added — no migration here): {@code loan(disbursed_on)},
 * {@code payment(paid_on)} and {@code loan_application(loan_id)}; the profile join is the only part
 * that is not a straight range scan.
 */
@Component
public class TransactionLedgerQuery {

    /**
     * The borrower per loan (loan → application → customer_profile), the SQL twin of
     * {@code TransactionService.profilesByLoanId}: the highest application id that has a profile wins.
     * Joined here, not hydrated afterwards, because the ledger filters and searches on borrower name
     * and mobile — they have to be in the row before the WHERE.
     *
     * <p>The repayment half drops {@code REJECTED}: a rejected repayment never moved money (the proof
     * did not match a transfer), so it is not a transaction — it lives on the loan's own Repayment
     * tab. Same rule as the in-memory filter it replaces, and it is in the CTE (not a clause on the
     * outer query) so the count, the totals and every facet inherit it; no caller can forget it.
     * The pending-vs-verified split below is a separate question and stays out of the CTE.
     */
    private static final String LEDGER_CTE = """
            with prof as (
                select distinct on (a.loan_id) a.loan_id, p.full_name, p.pan, p.mobile
                from loan_application a
                join customer_profile p on p.application_id = a.id
                where a.loan_id is not null
                order by a.loan_id, a.id desc
            ), ledger as (
                select 'D-' || l.id as id, 'DISBURSAL' as type, 'OUTGOING' as direction, l.id as row_id,
                       l.id as loan_id, l.customer_id, pr.full_name as borrower_name, pr.pan, pr.mobile,
                       coalesce(l.net_disbursed, 0) as amount_paise, l.disbursal_txn_ref as txn_ref,
                       cast(l.status as text) as status, l.disbursed_on as txn_date,
                       cast(null as text) as proof_url, l.due_date as due_date, l.grace_days as grace_days
                from loan l
                left join prof pr on pr.loan_id = l.id
                union all
                select 'P-' || p.id, 'REPAYMENT', 'INCOMING', p.id,
                       p.loan_id, l.customer_id, pr.full_name, pr.pan, pr.mobile,
                       coalesce(p.amount, 0), p.txn_ref,
                       cast(p.status as text), p.paid_on,
                       p.proof_url, l.due_date, coalesce(l.grace_days, 1)
                from payment p
                left join loan l on l.id = p.loan_id
                left join prof pr on pr.loan_id = p.loan_id
                where p.status <> 'REJECTED'
            )
            """;

    /** The base clauses' inputs: the ledger's own server params, not column filters. */
    public record LedgerFilter(String needle, String direction, LocalDate from, LocalDate to) {
    }

    /** One aggregate over the filter: the row count and the three summary-card sums, in paise. */
    public record Totals(long count, long inPaise, long outPaise, long pendingInPaise) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionLedgerQuery(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * Sortable columns, each a fixed SQL expression — the request only picks a key. Every one ends in
     * a tie-break on (direction, row_id) so paging is stable across rows that share a date.
     */
    static final Map<String, String> SORTS = Map.of(
            "date", "t.txn_date",
            "repaymentDate", "t.due_date",
            "borrower", "lower(t.borrower_name)",
            "type", "t.type",
            "amount", "t.amount_paise",
            "reference", "lower(t.txn_ref)",
            "status", "t.status",
            "loan", "t.loan_id");

    /** A {@code date} column as the IST-midnight instant {@link SqlFilter}'s day bounds are compared with. */
    private static final String DATE_AS_IST = "(cast(%s as timestamp) at time zone 'Asia/Kolkata')";

    /**
     * Columns a staffer can filter on, each tied to SQL written here (same constant-SQL rule as
     * {@link CustomerBookQuery#FILTER_COLUMNS}).
     *
     * <ul>
     *   <li>{@code amount} is stored in paise and shown in rupees, so it is registered with display
     *       scale 100: the client's bound is scaled, the column stays bare.</li>
     *   <li>{@code status} is TEXT, deliberately not a checklist: the column holds a {@code LoanStatus}
     *       on a disbursal row and a {@code PaymentStatus} on a repayment row, and one dropdown mixing
     *       two enums is meaningless. It matches the stored value, as the cell shows it.</li>
     *   <li>{@code type} is a two-value checklist (the only faceted column). Identity columns — the
     *       borrower, the reference, the loan — are TEXT only, so there is never a dropdown of names
     *       or txn refs.</li>
     *   <li>No {@code direction} column: the Incoming/Outgoing tabs are a server param that rides in
     *       {@link LedgerFilter}, and a column filter on it would let the two argue.</li>
     * </ul>
     */
    public static final FilterColumns FILTER_COLUMNS = FilterColumns.builder()
            .column("date", DATE_AS_IST.formatted("t.txn_date"), DAY)
            .column("repaymentDate", DATE_AS_IST.formatted("t.due_date"), DAY)
            .column("borrower", "t.borrower_name", TEXT)
            .column("type", "t.type", IN, TEXT)
            .scaled("amount", "t.amount_paise", 100, NUM)
            .column("reference", "t.txn_ref", TEXT)
            .column("status", "t.status", TEXT)
            .column("loan", "t.loan_id", TEXT)
            .build();

    /** One page, in the order asked for ({@code null} sort = newest first), proof still the raw S3 key. */
    public List<TransactionView> page(LedgerFilter filter, FilterSpec spec, int offset, int limit,
            String sort, String dir) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        params.addValue("limit", limit).addValue("offset", offset);
        String column = SORTS.getOrDefault(sort == null ? "date" : sort, SORTS.get("date"));
        String direction = "asc".equalsIgnoreCase(dir) ? "asc" : "desc";
        String sql = LEDGER_CTE
                + "select t.id, t.type, t.direction, t.loan_id, t.customer_id, t.borrower_name, t.pan,"
                + " t.amount_paise, t.txn_ref, t.status, t.txn_date, t.proof_url, t.due_date, t.grace_days"
                + " from ledger t" + where
                + " order by " + column + " " + direction + " nulls last, t.direction " + direction
                + ", t.row_id " + direction
                + " limit :limit offset :offset";
        return jdbc.query(sql, params, (rs, i) -> new TransactionView(
                rs.getString("id"), rs.getString("type"), rs.getString("direction"),
                nullableLong(rs, "loan_id"), nullableLong(rs, "customer_id"),
                rs.getString("borrower_name"), rs.getString("pan"),
                rs.getLong("amount_paise"), rs.getString("txn_ref"), rs.getString("status"),
                rs.getObject("txn_date", LocalDate.class), rs.getString("proof_url"),
                rs.getObject("due_date", LocalDate.class), rs.getInt("grace_days")));
    }

    /**
     * The row count and the three card sums over the WHOLE filter, in one pass — never a sum of the
     * page. Only a VERIFIED repayment is inflow (a pending claim may still be rejected); the rest of
     * the incoming rows are {@code pendingInPaise}. Rejected ones are not in the ledger at all.
     */
    public Totals totals(LedgerFilter filter, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, spec, params);
        String sql = LEDGER_CTE + "select count(*) as n,"
                + " coalesce(sum(t.amount_paise) filter (where t.direction = 'INCOMING' and t.status = 'VERIFIED'), 0) as in_paise,"
                + " coalesce(sum(t.amount_paise) filter (where t.direction = 'OUTGOING'), 0) as out_paise,"
                + " coalesce(sum(t.amount_paise) filter (where t.direction = 'INCOMING' and t.status <> 'VERIFIED'), 0) as pending_paise"
                + " from ledger t" + where;
        return jdbc.queryForObject(sql, params, (rs, i) ->
                new Totals(rs.getLong("n"), rs.getLong("in_paise"), rs.getLong("out_paise"), rs.getLong("pending_paise")));
    }

    /**
     * The distinct values of one faceted column, inside the same base clauses the list uses (period,
     * direction, search) so a dropdown never offers a value the rows cannot have; the other columns'
     * filters narrow it and the column's own is dropped by {@link SqlFacets}.
     */
    public SqlFacets.Facet facet(LedgerFilter filter, FilterSpec spec, String column) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> base = baseClauses(filter, params);
        return SqlFacets.facet(jdbc, LEDGER_CTE, "ledger t", base.isEmpty() ? null : String.join(" and ", base),
                spec, FILTER_COLUMNS, column, params);
    }

    /** Base clauses first, the column filters ANDed last — a filter can only narrow. */
    private static String where(LedgerFilter f, FilterSpec spec, MapSqlParameterSource p) {
        List<String> base = baseClauses(f, p);
        String filters = SqlFilter.and(spec, FILTER_COLUMNS, p);
        if (base.isEmpty()) {
            return filters.isEmpty() ? "" : " where true" + filters;
        }
        return " where " + String.join(" and ", base) + filters;
    }

    private static List<String> baseClauses(LedgerFilter f, MapSqlParameterSource p) {
        List<String> clauses = new ArrayList<>();
        if ("INCOMING".equals(f.direction()) || "OUTGOING".equals(f.direction())) {
            p.addValue("direction", f.direction());
            clauses.add("t.direction = :direction");
        }
        // Inclusive on both ends; a bound also drops a row with no date, as the old windowed finders did.
        if (f.from() != null) {
            p.addValue("from", java.sql.Date.valueOf(f.from()));
            clauses.add("t.txn_date >= :from");
        }
        if (f.to() != null) {
            p.addValue("to", java.sql.Date.valueOf(f.to()));
            clauses.add("t.txn_date <= :to");
        }
        if (f.needle() != null && !f.needle().isEmpty()) {
            // Borrower name / mobile / loan id — what the search box promises.
            p.addValue("needle", "%" + escapeLike(f.needle()) + "%");
            clauses.add("(lower(t.borrower_name) like :needle escape '\\'"
                    + " or t.mobile like :needle escape '\\'"
                    + " or cast(t.loan_id as text) like :needle escape '\\')");
        }
        return clauses;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
