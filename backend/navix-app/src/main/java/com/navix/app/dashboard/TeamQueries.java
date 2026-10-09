package com.navix.app.dashboard;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * SQL behind the people-centred surfaces: the team snapshot, the credit and telecaller leaderboard
 * inputs, and the daily series of the role views. Staff are matched on their numeric id
 * ({@code created_by_staff_id}, {@code sanctioned_by}, {@code actor_id}), never on a display name.
 */
@Component
public class TeamQueries {

    private final MetricQueries metrics;

    public TeamQueries(MetricQueries metrics) {
        this.metrics = metrics;
    }

    private static MapSqlParameterSource p(List<Long> ids, LocalDate from, LocalDate to) {
        List<Long> safe = ids.isEmpty() ? List.of(-1L) : ids;
        return new MapSqlParameterSource().addValue("from", from).addValue("to", to).addValue("ids", safe)
                .addValue("idStrs", safe.stream().map(String::valueOf).toList());
    }

    // ---- team snapshot ------------------------------------------------------------------

    /** Staff headcount per status (ACTIVE / INVITED / DISABLED). */
    public Map<String, Long> staffByStatus() {
        Map<String, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select status, count(*) as n from staff_user group by status",
                new MapSqlParameterSource(), rs -> {
                    out.put(rs.getString("status"), rs.getLong("n"));
                });
        return out;
    }

    /** ACTIVE staff per role. */
    public Map<String, Long> activeStaffByRole() {
        Map<String, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select role, count(*) as n from staff_user where status = 'ACTIVE'"
                        + " group by role order by role",
                new MapSqlParameterSource(), rs -> {
                    out.put(rs.getString("role"), rs.getLong("n"));
                });
        return out;
    }

    // ---- credit leaderboard -------------------------------------------------------------

    public record CreditRaw(long sanctioned, long rejected, long dueRepayable, long dueCollected) {
    }

    /** Sanctions/rejections by each staffer in the window, plus repayment on loans they sanctioned that fell due. */
    public Map<Long, CreditRaw> creditRaw(List<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, long[]> acc = new LinkedHashMap<>();
        metrics.jdbc().query("select e.actor_id, e.action, count(distinct e.application_id) as n"
                        + " from application_event e where e.action in ('SANCTION', 'REJECT_LEAD')"
                        + " and e.actor_id in (:idStrs) and " + MetricSql.ist("e.at") + " between :from and :to"
                        + " group by 1, 2",
                p(ids, from, to), rs -> {
                    long[] a = acc.computeIfAbsent(Long.valueOf(rs.getString("actor_id")), k -> new long[4]);
                    a["SANCTION".equals(rs.getString("action")) ? 0 : 1] = rs.getLong("n");
                });
        metrics.jdbc().query("select a.sanctioned_by as staff, coalesce(sum(l.total_repayable), 0) as due,"
                        + " coalesce(sum((select coalesce(sum(py.amount), 0) from payment py"
                        + " where py.loan_id = l.id and py.status = 'VERIFIED')), 0) as paid"
                        + " from loan_application a join loan l on l.id = a.loan_id"
                        + " where a.sanctioned_by in (:ids) and l.due_date <= :to group by 1",
                p(ids, from, to), rs -> {
                    long[] a = acc.computeIfAbsent(rs.getLong("staff"), k -> new long[4]);
                    a[2] = rs.getLong("due");
                    a[3] = rs.getLong("paid");
                });
        Map<Long, CreditRaw> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, new CreditRaw(a[0], a[1], a[2], a[3])));
        return out;
    }

    // ---- telecaller leaderboard ---------------------------------------------------------

    public record TelecallerRaw(long leads, long converted, long contacted, long calls) {
    }

    /** Leads each telecaller created in the window, how many converted / were reached, and their call volume. */
    public Map<Long, TelecallerRaw> telecallerRaw(List<Long> ids, LocalDate from, LocalDate to) {
        String day = MetricSql.ist("ld.created_at");
        Map<Long, long[]> acc = new LinkedHashMap<>();
        metrics.jdbc().query("select ld.created_by_staff_id as staff, count(*) as leads,"
                        + " count(*) filter (where ld.pan is not null and exists (select 1 from customer_profile cp"
                        + " join loan_application a on a.id = cp.application_id join loan l on l.id = a.loan_id"
                        + " where cp.pan = ld.pan and l.disbursed_on >= " + day + ")) as converted,"
                        + " count(*) filter (where ld.call_status = 'CONNECTED' or exists (select 1"
                        + " from customer_profile cp2 join loan_application a2 on a2.id = cp2.application_id"
                        + " join customer_call_log cl on cl.customer_id = a2.customer_id"
                        + " where cp2.mobile = ld.mobile)) as contacted"
                        + " from lead ld where ld.created_by_staff_id in (:ids) and " + day + " between :from and :to"
                        + " group by 1",
                p(ids, from, to), rs -> {
                    long[] a = acc.computeIfAbsent(rs.getLong("staff"), k -> new long[4]);
                    a[0] = rs.getLong("leads");
                    a[1] = rs.getLong("converted");
                    a[2] = rs.getLong("contacted");
                });
        for (Map.Entry<Long, Long> e : callsByStaff(ids, from, to).entrySet()) {
            acc.computeIfAbsent(e.getKey(), k -> new long[4])[3] = e.getValue();
        }
        Map<Long, TelecallerRaw> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, new TelecallerRaw(a[0], a[1], a[2], a[3])));
        return out;
    }

    public Map<Long, Long> callsByStaff(List<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query("select cl.created_by_staff_id as staff, count(*) as n from customer_call_log cl"
                        + " where cl.created_by_staff_id in (:ids) and " + MetricSql.ist("cl.created_at")
                        + " between :from and :to group by 1",
                p(ids, from, to), rs -> {
                    out.put(rs.getLong("staff"), rs.getLong("n"));
                });
        return out;
    }

    // ---- role-view cards ----------------------------------------------------------------

    /** {leads, callbacks} logged by the roster in the window (calls = {@link #callsByStaff}). */
    public long[] telecallerCards(List<Long> ids, LocalDate from, LocalDate to) {
        return metrics.jdbc().queryForObject("select"
                        + " (select count(*) from lead ld where ld.created_by_staff_id in (:ids) and "
                        + MetricSql.ist("ld.created_at") + " between :from and :to) as leads,"
                        + " (select count(*) from customer_call_log cl where cl.created_by_staff_id in (:ids)"
                        + " and cl.outcome = 'CALLBACK' and " + MetricSql.ist("cl.created_at")
                        + " between :from and :to) as callbacks",
                p(ids, from, to), (rs, i) -> new long[] {rs.getLong("leads"), rs.getLong("callbacks")});
    }

    /** Leads converted (PAN matched an applicant disbursed since) among leads the roster created in the window. */
    public long telecallerConversions(List<Long> ids, LocalDate from, LocalDate to) {
        return telecallerRaw(ids, from, to).values().stream().mapToLong(TelecallerRaw::converted).sum();
    }

    // ---- daily series -------------------------------------------------------------------

    private Map<LocalDate, Long> byDay(String sql, MapSqlParameterSource params) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        metrics.jdbc().query(sql, params, rs -> {
            out.put(rs.getObject("d", LocalDate.class), rs.getLong("n"));
        });
        return out;
    }

    /** Files assigned (ASSIGN / REASSIGN naming the roster) per day. */
    public Map<LocalDate, Long> assignedByDay(List<Long> ids, LocalDate from, LocalDate to) {
        return byDay("select ev.day as d, count(distinct ev.application_id) as n from (" + MetricSql.ASSIGN_EVENTS
                + ") ev where ev.staff_id in (:ids) and ev.day between :from and :to group by 1", p(ids, from, to));
    }

    /** Follow-ups per day: calls the roster logged plus files they parked with a request for a document. */
    public Map<LocalDate, Long> followupsByDay(List<Long> ids, LocalDate from, LocalDate to) {
        return byDay("select u.d, sum(u.n) as n from ("
                + "select " + MetricSql.ist("cl.created_at") + " as d, count(*) as n from customer_call_log cl"
                + " where cl.created_by_staff_id in (:ids) and " + MetricSql.ist("cl.created_at")
                + " between :from and :to group by 1"
                + " union all select " + MetricSql.ist("e.at") + " as d, count(*) as n from application_event e"
                + " where e.action = 'MARK_PENDING' and e.actor_id in (:idStrs) and " + MetricSql.ist("e.at")
                + " between :from and :to group by 1) u group by u.d", p(ids, from, to));
    }

    public Map<LocalDate, Long> leadsByDay(List<Long> ids, LocalDate from, LocalDate to) {
        return byDay("select " + MetricSql.ist("ld.created_at") + " as d, count(*) as n from lead ld"
                + " where ld.created_by_staff_id in (:ids) and " + MetricSql.ist("ld.created_at")
                + " between :from and :to group by 1", p(ids, from, to));
    }

    public Map<LocalDate, Long> callsByDay(List<Long> ids, LocalDate from, LocalDate to) {
        return byDay("select " + MetricSql.ist("cl.created_at") + " as d, count(*) as n from customer_call_log cl"
                + " where cl.created_by_staff_id in (:ids) and " + MetricSql.ist("cl.created_at")
                + " between :from and :to group by 1", p(ids, from, to));
    }

    /** Loans sanctioned by the roster per sanction day: {unclosed, closed}. */
    public Map<LocalDate, long[]> sanctionClosureByDay(List<Long> ids, LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> out = new LinkedHashMap<>();
        metrics.jdbc().query("select " + MetricSql.ist("a.sanctioned_at") + " as d,"
                        + " count(*) filter (where l.status <> 'CLOSED') as unclosed,"
                        + " count(*) filter (where l.status = 'CLOSED') as closed"
                        + " from loan_application a join loan l on l.id = a.loan_id"
                        + " where a.sanctioned_by in (:ids) and a.sanctioned_at is not null and "
                        + MetricSql.ist("a.sanctioned_at") + " between :from and :to group by 1",
                p(ids, from, to), rs -> {
                    out.put(rs.getObject("d", LocalDate.class), new long[] {rs.getLong("unclosed"), rs.getLong("closed")});
                });
        return out;
    }

    // ---- disbursement / accounts cards --------------------------------------------------

    /** Applications whose disbursal failed, created in the window. */
    public long disbursalFailed(LocalDate from, LocalDate to) {
        Long n = metrics.jdbc().queryForObject(MetricSql.SEG + "select count(*) from seg a"
                        + " where a.status = 'DISBURSEMENT_FAILED' and a.created_on between :from and :to",
                p(List.of(), from, to), Long.class);
        return n == null ? 0 : n;
    }

    /** Payments by status whose payment date falls in the window: {count, paise}. */
    public Map<String, long[]> paymentsByStatus(LocalDate from, LocalDate to) {
        Map<String, long[]> out = new LinkedHashMap<>();
        metrics.jdbc().query("select p.status, count(*) as n, coalesce(sum(p.amount), 0) as amt from payment p"
                        + " where p.paid_on between :from and :to group by 1",
                p(List.of(), from, to), rs -> {
                    out.put(rs.getString("status"), new long[] {rs.getLong("n"), rs.getLong("amt")});
                });
        return out;
    }

    /** Repayments waiting for the accountant right now (a live queue, not windowed): {count, paise}. */
    public long[] pendingVerification() {
        return metrics.jdbc().queryForObject("select count(*) as n, coalesce(sum(p.amount), 0) as amt from payment p"
                        + " where p.status = 'PENDING_VERIFICATION'",
                new MapSqlParameterSource(), (rs, i) -> new long[] {rs.getLong("n"), rs.getLong("amt")});
    }
}
