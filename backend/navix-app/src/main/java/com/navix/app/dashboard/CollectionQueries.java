package com.navix.app.dashboard;

import com.navix.app.dashboard.MetricSql.Metric;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * SQL behind the collection surfaces: the collection analysis, the staff allocation table, the
 * promise-to-pay figures and the raw inputs of the collection leaderboard. Money collected is always
 * {@code payment.status = 'VERIFIED'} -- never {@code collection_payment}, which would count the same
 * rupee twice through {@code ledger_payment_id}.
 */
@Component
public class CollectionQueries {

    private final MetricQueries metrics;

    public CollectionQueries(MetricQueries metrics) {
        this.metrics = metrics;
    }

    public record AnalysisAgg(String key, long loans, long principal, long repayable, long collectedCount,
                              long collected) {
    }

    /** Loans due on {@code date}, grouped by credit executive or state. */
    public List<AnalysisAgg> analysis(DashboardScope scope, String groupBy, LocalDate date) {
        String sql = MetricSql.query(Metric.COLLECTION_GROUP, scope, "select split_part(f.grp, ':', 2) as k,"
                + " count(*) as loans, coalesce(sum(l.principal), 0) as principal,"
                + " coalesce(sum(l.total_repayable), 0) as repayable,"
                + " count(*) filter (where l.status = 'CLOSED' or v.paid >= l.total_repayable) as collected_n,"
                + " coalesce(sum(v.paid), 0) as paid"
                + " from {F} join loan l on l.id = f.loan_id"
                + " cross join lateral (select coalesce(sum(p.amount), 0) as paid from payment p"
                + " where p.loan_id = l.id and p.status = 'VERIFIED') v group by 1 order by 1");
        return metrics.jdbc().query(sql, metrics.params(Metric.COLLECTION_GROUP, scope, date, date, null, groupBy),
                (rs, i) -> new AnalysisAgg(rs.getString("k"), rs.getLong("loans"), rs.getLong("principal"),
                        rs.getLong("repayable"), rs.getLong("collected_n"), rs.getLong("paid")));
    }

    private MapSqlParameterSource staffParams(DashboardScope scope, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("from", from).addValue("to", to)
                .addValue("allStaff", scope.allStaff()).addValue("ids", scope.sqlIds());
    }

    /** Cases whose loan closed in the window, per officer. */
    public Map<Long, Long> closedByOfficer(DashboardScope scope, LocalDate from, LocalDate to) {
        Map<Long, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select cc.assigned_officer_id as staff, count(distinct cc.id) as n"
                        + " from collection_case cc join loan l on l.id = cc.loan_id"
                        + " where l.status = 'CLOSED' and l.closed_on between :from and :to"
                        + " and cc.assigned_officer_id is not null"
                        + " and (:allStaff or cc.assigned_officer_id in (:ids)) group by 1",
                staffParams(scope, from, to), rs -> {
                    out.put(rs.getLong("staff"), rs.getLong("n"));
                });
        return out;
    }

    /** Verified collections in the window on an officer's cases: loans paid and rupees, per segment. */
    public record Collected(long staffId, String segment, long loans, long paise) {
    }

    public List<Collected> collectedByOfficer(DashboardScope scope, LocalDate from, LocalDate to) {
        String sql = MetricSql.SEG + "select cc.assigned_officer_id as staff, a.segment,"
                + " count(distinct p.loan_id) as loans, coalesce(sum(p.amount), 0) as amt"
                + " from collection_case cc join loan l on l.id = cc.loan_id"
                + " join payment p on p.loan_id = l.id and p.status = 'VERIFIED' and p.paid_on between :from and :to"
                + " join seg a on a.loan_id = l.id"
                + " where cc.assigned_officer_id is not null and (:allStaff or cc.assigned_officer_id in (:ids))"
                + " group by 1, 2";
        return metrics.jdbc().query(sql, staffParams(scope, from, to), (rs, i) -> new Collected(rs.getLong("staff"),
                rs.getString("segment"), rs.getLong("loans"), rs.getLong("amt")));
    }

    /** Loans paid / rupees collected in the window on cases of the roster (the collection card). */
    public long[] collectedTotals(DashboardScope scope, LocalDate from, LocalDate to) {
        return metrics.jdbc().queryForObject("select count(distinct p.loan_id) as loans,"
                        + " coalesce(sum(p.amount), 0) as amt from payment p"
                        + " where p.status = 'VERIFIED' and p.paid_on between :from and :to"
                        + " and exists (select 1 from collection_case cc where cc.loan_id = p.loan_id"
                        + " and (:allStaff or cc.assigned_officer_id in (:ids)))",
                staffParams(scope, from, to), (rs, i) -> new long[] {rs.getLong("loans"), rs.getLong("amt")});
    }

    /**
     * Money that arrived on an officer's cases after they promised to pay: a verified payment on a case
     * paid on or after a {@code PROMISE_TO_PAY} the same officer logged. Per payment day.
     */
    public Map<LocalDate, Long> ptpCollectedByDay(DashboardScope scope, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select p.paid_on as d, coalesce(sum(p.amount), 0) as amt from payment p"
                        + " join collection_case cc on cc.loan_id = p.loan_id"
                        + " where p.status = 'VERIFIED' and p.paid_on between :from and :to"
                        + " and cc.assigned_officer_id in (:ids)"
                        + " and exists (select 1 from interaction_log i where i.collection_case_id = cc.id"
                        + " and i.outcome = 'PROMISE_TO_PAY' and i.logged_by_staff_id in (:ids)"
                        + " and " + MetricSql.ist("i.logged_at") + " <= p.paid_on) group by 1",
                staffParams(scope, from, to), rs -> {
                    out.put(rs.getObject("d", LocalDate.class), rs.getLong("amt"));
                });
        return out;
    }

    /** {total after assignment, same-day, latest payment date (nullable)} for the roster's cases. */
    public record PtpTotals(long afterAssignment, long sameDay, LocalDate latest) {
    }

    public PtpTotals ptpTotals(DashboardScope scope, LocalDate from, LocalDate to) {
        String assigned = MetricSql.ist("cc.created_at");
        return metrics.jdbc().queryForObject("select coalesce(sum(p.amount), 0) as total,"
                        + " coalesce(sum(p.amount) filter (where p.paid_on = " + assigned + "), 0) as same_day,"
                        + " max(p.paid_on) as latest from collection_case cc"
                        + " join payment p on p.loan_id = cc.loan_id and p.status = 'VERIFIED'"
                        + " and p.paid_on >= " + assigned + " and p.paid_on between :from and :to"
                        + " where cc.assigned_officer_id in (:ids)",
                staffParams(scope, from, to), (rs, i) -> new PtpTotals(rs.getLong("total"), rs.getLong("same_day"),
                        rs.getObject("latest", LocalDate.class)));
    }

    /** One case created in the window: who owns it and whether its loan has closed. */
    public record CaseRow(long staffId, long loanId, boolean closed) {
    }

    public List<CaseRow> casesCreated(DashboardScope scope, LocalDate from, LocalDate to) {
        return metrics.jdbc().query("select cc.assigned_officer_id as staff, cc.loan_id, (l.status = 'CLOSED') as closed"
                        + " from collection_case cc join loan l on l.id = cc.loan_id"
                        + " where cc.assigned_officer_id is not null and " + MetricSql.ist("cc.created_at")
                        + " between :from and :to and (:allStaff or cc.assigned_officer_id in (:ids))",
                staffParams(scope, from, to), (rs, i) -> new CaseRow(rs.getLong("staff"), rs.getLong("loan_id"),
                        rs.getBoolean("closed")));
    }

    /** Verified rupees collected in the window on the loans of cases created in the window, per officer. */
    public Map<Long, Long> collectedOnCreatedCases(DashboardScope scope, LocalDate from, LocalDate to) {
        Map<Long, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select cc.assigned_officer_id as staff, coalesce(sum(p.amount), 0) as amt"
                        + " from collection_case cc"
                        + " join payment p on p.loan_id = cc.loan_id and p.status = 'VERIFIED'"
                        + " and p.paid_on between :from and :to"
                        + " where cc.assigned_officer_id is not null and " + MetricSql.ist("cc.created_at")
                        + " between :from and :to and (:allStaff or cc.assigned_officer_id in (:ids)) group by 1",
                staffParams(scope, from, to), rs -> {
                    out.put(rs.getLong("staff"), rs.getLong("amt"));
                });
        return out;
    }
}
