package com.navix.app.dashboard;

import com.navix.app.dashboard.DashboardDtos.RateRow;
import com.navix.app.dashboard.DashboardDtos.TargetRow;
import com.navix.app.dashboard.MetricSql.Metric;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * SQL behind the Admin business tabs (snapshot, monthly, daily, geo, companies, calendar) and the
 * monthly targets. Anything countable is read through {@link MetricQueries} so it shares its fragment
 * with the drill-down; what lives here is the money and rollups no fragment expresses.
 */
@Component
public class BusinessQueries {

    /** Default collection target when a month has none: 88.00% in basis points. */
    public static final int DEFAULT_TARGET_BP = 8800;

    private static final String SETTLED_EXISTS = MetricSql.SETTLED_EXISTS;

    private final MetricQueries metrics;

    public BusinessQueries(MetricQueries metrics) {
        this.metrics = metrics;
    }

    /** Verified money in the window split by how the loan stands: closed, settled, still open (part payments). */
    public long[] collectedByClosingType(LocalDate from, LocalDate to) {
        String sql = "select"
                + " coalesce(sum(p.amount) filter (where l.status = 'CLOSED' and not " + SETTLED_EXISTS + "), 0) as closed,"
                + " coalesce(sum(p.amount) filter (where l.status = 'CLOSED' and " + SETTLED_EXISTS + "), 0) as settled,"
                + " coalesce(sum(p.amount) filter (where l.status <> 'CLOSED'), 0) as part"
                + " from payment p join loan l on l.id = p.loan_id"
                + " where p.status = 'VERIFIED' and p.paid_on between :from and :to";
        return metrics.jdbc().queryForObject(sql, new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new long[] {rs.getLong("closed"), rs.getLong("settled"), rs.getLong("part")});
    }

    /** PF % / ROI % table rows: loans disbursed in the window grouped by rate; New = Fresh, Repeat = Re-loan. */
    public List<RateRow> rateTable(Metric rate, DashboardScope scope, LocalDate from, LocalDate to) {
        String sql = MetricSql.query(rate, scope, "select f.grp,"
                + " count(*) filter (where f.segment = 'FRESH') as new_n,"
                + " count(*) filter (where f.segment = 'RELOAN') as repeat_n, count(*) as total,"
                + " coalesce(sum(l.principal), 0) as principal, coalesce(sum(l.net_disbursed), 0) as net,"
                + " coalesce(sum(l.total_repayable), 0) as repayable"
                + " from {F} join loan l on l.id = f.loan_id group by f.grp order by cast(f.grp as numeric)");
        return metrics.jdbc().query(sql, metrics.params(rate, scope, from, to, null, null),
                (rs, i) -> new RateRow(Double.parseDouble(rs.getString("grp")), rs.getLong("new_n"),
                        rs.getLong("repeat_n"), rs.getLong("total"), rs.getLong("principal"), rs.getLong("net"),
                        rs.getLong("repayable")));
    }

    /** What falls due in a month, what was collected against it, and how many were pre-closed. */
    public record MonthCollection(long due, long repayable, long collected, long preclosed) {
    }

    /** Collection per due-month over [from, to], scoped to the caller's loans (a collection view sees its team's). */
    public Map<String, MonthCollection> monthlyCollection(DashboardScope scope, LocalDate from, LocalDate to) {
        String sql = MetricSql.query(Metric.COLLECTION_MONTH, scope, "select f.grp as month, count(*) as due,"
                + " coalesce(sum(f.amount), 0) as repayable,"
                + " coalesce(sum((select coalesce(sum(p.amount), 0) from payment p"
                + " where p.loan_id = f.loan_id and p.status = 'VERIFIED')), 0) as collected,"
                + " count(*) filter (where l.status = 'CLOSED' and l.closed_on < l.due_date) as preclosed"
                + " from {F} join loan l on l.id = f.loan_id group by f.grp");
        Map<String, MonthCollection> out = new LinkedHashMap<>();
        metrics.jdbc().query(sql, metrics.params(Metric.COLLECTION_MONTH, scope, from, to, null, null), rs -> {
            out.put(rs.getString("month"), new MonthCollection(rs.getLong("due"), rs.getLong("repayable"),
                    rs.getLong("collected"), rs.getLong("preclosed")));
        });
        return out;
    }

    /** Principal disbursed per month (YYYY-MM) in [from, to]. */
    public Map<String, Long> disbursedByMonth(LocalDate from, LocalDate to) {
        Map<String, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select to_char(l.disbursed_on, 'YYYY-MM') as month, coalesce(sum(l.principal), 0) as p"
                        + " from loan l where l.disbursed_on between :from and :to group by 1",
                new MapSqlParameterSource("from", from).addValue("to", to),
                rs -> {
                    out.put(rs.getString("month"), rs.getLong("p"));
                });
        return out;
    }

    /**
     * Marketing conversion per lead-creation month: a lead converts when its PAN matches an applicant
     * who was disbursed on or after the day the lead was created. Value = {leads, converted}.
     */
    public Map<String, long[]> marketing(LocalDate from, LocalDate to) {
        String day = "cast(ld.created_at at time zone 'Asia/Kolkata' as date)";
        String sql = "select to_char(" + day + ", 'YYYY-MM') as month, count(*) as leads,"
                + " count(*) filter (where ld.pan is not null and exists (select 1 from customer_profile cp"
                + " join loan_application a on a.id = cp.application_id join loan l on l.id = a.loan_id"
                + " where cp.pan = ld.pan and l.disbursed_on >= " + day + ")) as converted"
                + " from lead ld where " + day + " between :from and :to group by 1";
        Map<String, long[]> out = new LinkedHashMap<>();
        metrics.jdbc().query(sql, new MapSqlParameterSource("from", from).addValue("to", to), rs -> {
            out.put(rs.getString("month"), new long[] {rs.getLong("leads"), rs.getLong("converted")});
        });
        return out;
    }

    /** Processing fee (GST excluded -- it is not revenue) per disbursal day. */
    public Map<LocalDate, Long> pfByDay(LocalDate from, LocalDate to) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select l.disbursed_on as d, coalesce(sum(l.processing_fee), 0) as pf from loan l"
                        + " where l.disbursed_on between :from and :to group by 1",
                new MapSqlParameterSource("from", from).addValue("to", to),
                rs -> {
                    out.put(rs.getObject("d", LocalDate.class), rs.getLong("pf"));
                });
        return out;
    }

    /** Ids of loans closed in the window, so their frozen interest/penalty can be priced. */
    public List<Long> closedLoanIds(LocalDate from, LocalDate to) {
        return metrics.jdbc().queryForList("select l.id from loan l where l.status = 'CLOSED'"
                        + " and l.closed_on between :from and :to",
                new MapSqlParameterSource("from", from).addValue("to", to), Long.class);
    }

    /** Cases and rollups behind a geography row. */
    public record GeoAgg(String grp, String extra, long cases, long principal, long closed, long due) {
    }

    /** Per state / pincode: cases (loans disbursed in the window), principal, and the due/closed split by {@code to}. */
    public List<GeoAgg> geo(Metric geoMetric, DashboardScope scope, LocalDate from, LocalDate to) {
        String sql = MetricSql.query(geoMetric, scope, "select f.grp, max(f.extra) as extra, count(*) as cases,"
                + " coalesce(sum(f.amount), 0) as principal,"
                + " count(*) filter (where l.due_date <= :to and l.status = 'CLOSED') as closed,"
                + " count(*) filter (where l.due_date <= :to) as due"
                + " from {F} join loan l on l.id = f.loan_id group by f.grp order by principal desc, f.grp");
        return metrics.jdbc().query(sql, metrics.params(geoMetric, scope, from, to, null, null),
                (rs, i) -> new GeoAgg(rs.getString("grp"), rs.getString("extra"), rs.getLong("cases"),
                        rs.getLong("principal"), rs.getLong("closed"), rs.getLong("due")));
    }

    public record CompanyUsersAgg(String company, long users, Long avgSalary, String employmentType) {
    }

    /** Distinct customers per employer for applications created in the window, biggest first. */
    public List<CompanyUsersAgg> companyUsers(LocalDate from, LocalDate to) {
        String sql = MetricSql.SEG + "select lower(btrim(cp.employer)) as company,"
                + " count(distinct a.customer_id) as users, cast(round(avg(cp.monthly_salary_paise)) as bigint) as avg_salary,"
                + " mode() within group (order by cp.employment_status) as emp"
                + " from seg a join customer_profile cp on cp.application_id = a.application_id"
                + " where a.created_on between :from and :to and nullif(btrim(cp.employer), '') is not null"
                + " group by 1 order by users desc, company";
        return metrics.jdbc().query(sql, new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new CompanyUsersAgg(rs.getString("company"), rs.getLong("users"),
                        (Long) rs.getObject("avg_salary", Long.class), rs.getString("emp")));
    }

    /** Distinct customers with a company on file in the window (a person is counted once overall). */
    public long distinctUsers(LocalDate from, LocalDate to) {
        String sql = MetricSql.SEG + "select count(distinct a.customer_id) from seg a"
                + " join customer_profile cp on cp.application_id = a.application_id"
                + " where a.created_on between :from and :to and nullif(btrim(cp.employer), '') is not null";
        Long n = metrics.jdbc().queryForObject(sql, new MapSqlParameterSource("from", from).addValue("to", to),
                Long.class);
        return n == null ? 0 : n;
    }

    public record CompanyPerfAgg(String company, long disbursed, long principal, long repayable, long collected) {
    }

    /** Loans disbursed in the window per company with what they owe and have paid. */
    public List<CompanyPerfAgg> companyPerformance(DashboardScope scope, LocalDate from, LocalDate to, int limit) {
        String sql = MetricSql.query(Metric.COMPANY, scope, "select f.grp as company, count(*) as disbursed,"
                + " coalesce(sum(f.amount), 0) as principal, coalesce(sum(l.total_repayable), 0) as repayable,"
                + " coalesce(sum((select coalesce(sum(p.amount), 0) from payment p"
                + " where p.loan_id = f.loan_id and p.status = 'VERIFIED')), 0) as collected"
                + " from {F} join loan l on l.id = f.loan_id group by f.grp order by principal desc, f.grp limit :limit");
        MapSqlParameterSource p = metrics.params(Metric.COMPANY, scope, from, to, null, null).addValue("limit", limit);
        return metrics.jdbc().query(sql, p, (rs, i) -> new CompanyPerfAgg(rs.getString("company"),
                rs.getLong("disbursed"), rs.getLong("principal"), rs.getLong("repayable"), rs.getLong("collected")));
    }

    // ---- targets ----------------------------------------------------------------------------

    public List<TargetRow> targets(YearMonth from, YearMonth to) {
        return metrics.jdbc().query("select month, disbursal_target_paise, collection_target_bp from monthly_target"
                        + " where month between :from and :to order by month",
                new MapSqlParameterSource("from", Date.valueOf(from.atDay(1))).addValue("to", Date.valueOf(to.atDay(1))),
                (rs, i) -> new TargetRow(YearMonth.from(rs.getObject("month", LocalDate.class)).toString(),
                        (Long) rs.getObject("disbursal_target_paise", Long.class), rs.getInt("collection_target_bp")));
    }

    public void saveTarget(YearMonth month, Long disbursalTargetPaise, int collectionTargetBp, String updatedBy) {
        metrics.jdbc().update("insert into monthly_target (month, disbursal_target_paise, collection_target_bp,"
                        + " updated_by, updated_at) values (:month, :paise, :bp, :by, now())"
                        + " on conflict (month) do update set disbursal_target_paise = excluded.disbursal_target_paise,"
                        + " collection_target_bp = excluded.collection_target_bp, updated_by = excluded.updated_by,"
                        + " updated_at = now()",
                new MapSqlParameterSource("month", Date.valueOf(month.atDay(1)))
                        .addValue("paise", disbursalTargetPaise).addValue("bp", collectionTargetBp)
                        .addValue("by", updatedBy));
    }
}
