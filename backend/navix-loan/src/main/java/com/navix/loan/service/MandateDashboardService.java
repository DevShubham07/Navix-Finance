package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The "Mandates &amp; debits" console: every eMandate attempt and every NACH debit, read-only.
 *
 * <p>Load shape is deliberate. {@code mandate} and {@code mandate_debit} are small and indexed, so every list
 * filters and pages on them FIRST; the customer name, mobile and current application are then fetched only
 * for the customer ids on that page ({@link #people}). A name/mobile search is resolved the same way —
 * against customers that hold a mandate, never the whole book. Nothing here joins {@code customer_profile}
 * across all customers.
 *
 * <p>Authorisation is an ALLOWLIST on the real JWT role — COLLECTION_HEAD and ADMIN, the same pair as the
 * Loans and refund registers — so DSA and every other role is refused by omission (CLAUDE.md section 7).
 * Nothing here writes: every action goes through the existing per-application/collections endpoints, which
 * keep their own guards.
 */
@Service
@RequiredArgsConstructor
public class MandateDashboardService {

    private static final Set<String> ALLOWED = Set.of("COLLECTION_HEAD", "ADMIN");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_PAGE_SIZE = 100;
    static final int SERIES_DAYS = 30;

    private final NamedParameterJdbcTemplate jdbc;

    // ---- views -------------------------------------------------------------------------------------

    public record Summary(long totalMandates, long activeMandates, long activeCustomers, long activeLimitPaise,
                          long pendingAuthorisation, long failed, long cancelled, long bankUnsupported,
                          long totalDebits, long debitsSucceeded, long debitedPaise, long debitsFailed,
                          long debitsInFlight, long inFlightPaise, long debitsCancelled, long refundsOwedPaise,
                          List<DayPoint> debitedByDay) {}

    public record DayPoint(LocalDate date, long debitedPaise, long count) {}

    /** {@code status} is the stored status, except a staff-cancelled mandate reads {@code CANCELLED}. */
    public record MandateRow(Long id, Long customerId, String customerName, String mobile, Long applicationId,
                             String applicationStatus, Long loanId, int attemptNo, String status, String umrn,
                             String bankName, String accountLast4, long maximumAmountPaise, String authSubMode,
                             String rejectReason, Instant createdAt, Instant authenticatedAt, Instant cancelledAt,
                             String cancelledBy, String cancelReason, long debitCount, long debitsSucceeded,
                             long debitedPaise, long debitsInFlight, Instant lastDebitAt) {}

    /** {@code actorName} null with trigger SCHEDULED means the automatic sweep presented it. */
    public record DebitRow(Long id, Long mandateId, String umrn, Long loanId, Long customerId, String customerName,
                           String mobile, Long applicationId, int attemptNo, long amountPaise, String status,
                           String digioStatus, String failureCode, String failureReason, String utr,
                           LocalDate settlementDate, String trigger, Long actorId, String actorName,
                           String actorRole, Integer dpdAtAttempt, int presentCount, boolean overpayment,
                           String digioTxnId, Instant createdAt, Instant updatedAt, String updatedBy) {}

    public record MandatePage(List<MandateRow> rows, long total, int page, int size) {}

    public record DebitPage(List<DebitRow> rows, long total, int page, int size, long succeededCount,
                            long succeededPaise) {}

    public record Actor(Long id, String name, String role) {}

    /** Name, mobile and current (newest) application of one customer. */
    private record Person(String name, String mobile, Long applicationId, String applicationStatus, Long loanId) {
        static final Person NONE = new Person(null, null, null, null, null);
    }

    // ---- summary -----------------------------------------------------------------------------------

    public Summary summary() {
        requireAllowed();
        Map<String, Object> m = jdbc.queryForMap("""
                select count(*) total,
                  count(*) filter (where status = 'ACTIVE' and cancelled_at is null) active,
                  count(distinct customer_id) filter (where status = 'ACTIVE' and cancelled_at is null) customers,
                  coalesce(sum(maximum_amount_paise) filter (where status = 'ACTIVE' and cancelled_at is null), 0) active_limit,
                  count(*) filter (where status = 'PENDING_AUTHORIZATION' and cancelled_at is null) pending,
                  count(*) filter (where status in ('AUTHORIZATION_FAILED', 'EXPIRED') and cancelled_at is null) failed,
                  count(*) filter (where cancelled_at is not null) cancelled,
                  count(*) filter (where status = 'BANK_UNSUPPORTED') unsupported
                from mandate
                """, Map.of());
        Map<String, Object> d = jdbc.queryForMap("""
                select count(*) total,
                  count(*) filter (where status = 'SUCCEEDED') ok,
                  coalesce(sum(amount_paise) filter (where status = 'SUCCEEDED'), 0) debited,
                  count(*) filter (where status in ('FAILED', 'FAILED_TECHNICAL')) failed,
                  count(*) filter (where status in ('SCHEDULED', 'IN_PROGRESS')) inflight,
                  coalesce(sum(amount_paise) filter (where status in ('SCHEDULED', 'IN_PROGRESS')), 0) inflight_amount,
                  count(*) filter (where status = 'CANCELLED') cancelled,
                  coalesce(sum(amount_paise) filter (where overpayment), 0) refunds
                from mandate_debit
                """, Map.of());
        // Money collected per day: the settlement date (when the bank moved it), else the day we learned of it.
        LocalDate today = LocalDate.now(IST);
        LocalDate from = today.minusDays(SERIES_DAYS - 1L);
        Map<LocalDate, DayPoint> byDay = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            byDay.put(day, new DayPoint(day, 0, 0));
        }
        jdbc.query("""
                select coalesce(settlement_date, (updated_at at time zone 'Asia/Kolkata')::date) day,
                       sum(amount_paise) amt, count(*) n
                from mandate_debit
                where status = 'SUCCEEDED'
                  and coalesce(settlement_date, (updated_at at time zone 'Asia/Kolkata')::date) between :from and :to
                group by 1
                """, new MapSqlParameterSource("from", from).addValue("to", today), rs -> {
            LocalDate day = rs.getDate("day").toLocalDate();
            byDay.put(day, new DayPoint(day, rs.getLong("amt"), rs.getLong("n")));
        });
        return new Summary(num(m, "total"), num(m, "active"), num(m, "customers"), num(m, "active_limit"),
                num(m, "pending"), num(m, "failed"), num(m, "cancelled"), num(m, "unsupported"),
                num(d, "total"), num(d, "ok"), num(d, "debited"), num(d, "failed"), num(d, "inflight"),
                num(d, "inflight_amount"), num(d, "cancelled"), num(d, "refunds"), new ArrayList<>(byDay.values()));
    }

    // ---- mandates ----------------------------------------------------------------------------------

    /**
     * Mandate attempts, newest first. {@code status}: ALL, ACTIVE, PENDING, FAILED, CANCELLED,
     * BANK_UNSUPPORTED. {@code q}: name, mobile, UMRN, bank, customer id, application id or mandate id.
     */
    public MandatePage mandates(String q, String status, int page, int size) {
        requireAllowed();
        int sz = pageSize(size);
        int pg = Math.max(page, 0);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1 = 1").append(mandateStatusClause(status));
        Search s = search(q, p);
        if (s != null) {
            where.append(" and (lower(coalesce(m.umrn, '')) like :like or lower(coalesce(m.bank_name, '')) like :like");
            s.orCustomer(where, "m.customer_id");
            if (s.number != null) {
                where.append(" or m.customer_id = :num or m.loan_application_id = :num or m.id = :num");
            }
            where.append(")");
        }
        long total = jdbc.queryForObject("select count(*) from mandate m" + where, p, Long.class);
        p.addValue("limit", sz).addValue("offset", (long) pg * sz);
        List<Object[]> raw = jdbc.query("""
                select m.id, m.customer_id, m.attempt_no, m.status, m.umrn, m.bank_name, m.account_last4,
                       m.maximum_amount_paise, m.auth_sub_mode, m.reject_reason, m.created_at, m.authenticated_at,
                       m.cancelled_at, m.cancelled_by, m.cancel_reason
                from mandate m""" + where + " order by m.id desc limit :limit offset :offset", p, (rs, i) -> new Object[] {
                rs.getLong("id"), rs.getLong("customer_id"), rs.getInt("attempt_no"), rs.getString("status"),
                rs.getString("umrn"), rs.getString("bank_name"), rs.getString("account_last4"),
                rs.getLong("maximum_amount_paise"), rs.getString("auth_sub_mode"), rs.getString("reject_reason"),
                instant(rs, "created_at"), instant(rs, "authenticated_at"), instant(rs, "cancelled_at"),
                rs.getString("cancelled_by"), rs.getString("cancel_reason")});
        if (raw.isEmpty()) {
            return new MandatePage(List.of(), total, pg, sz);
        }

        // Debit totals for THIS page's mandates only (indexed on mandate_id).
        List<Long> mandateIds = raw.stream().map(r -> (Long) r[0]).toList();
        Map<Long, long[]> debitAgg = new HashMap<>();
        Map<Long, Instant> lastDebit = new HashMap<>();
        jdbc.query("""
                select mandate_id, count(*) n,
                       count(*) filter (where status = 'SUCCEEDED') ok,
                       coalesce(sum(amount_paise) filter (where status = 'SUCCEEDED'), 0) debited,
                       count(*) filter (where status in ('SCHEDULED', 'IN_PROGRESS')) inflight,
                       max(created_at) last_at
                from mandate_debit where mandate_id in (:ids) group by mandate_id
                """, new MapSqlParameterSource("ids", mandateIds), rs -> {
            long id = rs.getLong("mandate_id");
            debitAgg.put(id, new long[] {rs.getLong("n"), rs.getLong("ok"), rs.getLong("debited"), rs.getLong("inflight")});
            lastDebit.put(id, instant(rs, "last_at"));
        });
        Map<Long, Person> people = people(raw.stream().map(r -> (Long) r[1]).distinct().toList());

        List<MandateRow> rows = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            Long id = (Long) r[0];
            Person who = people.getOrDefault((Long) r[1], Person.NONE);
            long[] agg = debitAgg.getOrDefault(id, new long[4]);
            Instant cancelledAt = (Instant) r[12];
            rows.add(new MandateRow(id, (Long) r[1], who.name(), who.mobile(), who.applicationId(),
                    who.applicationStatus(), who.loanId(), (Integer) r[2],
                    cancelledAt != null ? "CANCELLED" : (String) r[3], (String) r[4], (String) r[5], (String) r[6],
                    (Long) r[7], (String) r[8], (String) r[9], (Instant) r[10], (Instant) r[11], cancelledAt,
                    (String) r[13], (String) r[14], agg[0], agg[1], agg[2], agg[3], lastDebit.get(id)));
        }
        return new MandatePage(rows, total, pg, sz);
    }

    // ---- debits ------------------------------------------------------------------------------------

    /** Every debit presented against one mandate (its UMRN), newest first. */
    public List<DebitRow> debitsForMandate(Long mandateId) {
        requireAllowed();
        return withPeople(jdbc.query(DEBIT_SELECT + " where d.mandate_id = :mandateId order by d.id desc",
                new MapSqlParameterSource("mandateId", mandateId), MandateDashboardService::debitRow));
    }

    /**
     * Every debit across the book, newest first. {@code status}: ALL, SUCCEEDED, FAILED (incl. technical),
     * IN_FLIGHT, CANCELLED, SKIPPED, REFUND_OWED. {@code trigger}: ALL, SCHEDULED, MANUAL. {@code actorId}: the
     * staff member who presented it. {@code from}/{@code to}: presented-on dates, IST, inclusive. {@code q}:
     * name, mobile, UMRN, UTR, loan id or customer id.
     */
    public DebitPage debits(String q, String status, String trigger, Long actorId, LocalDate from, LocalDate to,
                            int page, int size) {
        requireAllowed();
        int sz = pageSize(size);
        int pg = Math.max(page, 0);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        switch (upper(status)) {
            case "SUCCEEDED" -> where.append(" and d.status = 'SUCCEEDED'");
            case "FAILED" -> where.append(" and d.status in ('FAILED', 'FAILED_TECHNICAL')");
            case "IN_FLIGHT" -> where.append(" and d.status in ('SCHEDULED', 'IN_PROGRESS')");
            case "CANCELLED" -> where.append(" and d.status = 'CANCELLED'");
            case "SKIPPED" -> where.append(" and d.status = 'SKIPPED'");
            case "REFUND_OWED" -> where.append(" and d.overpayment");
            default -> { }
        }
        String tr = upper(trigger);
        if (tr.equals("SCHEDULED") || tr.equals("MANUAL")) {
            where.append(" and d.triggered_by = :trigger");
            p.addValue("trigger", tr);
        }
        if (actorId != null) {
            where.append(" and d.actor_id = :actorId");
            p.addValue("actorId", actorId);
        }
        if (from != null) {
            where.append(" and d.created_at >= :fromTs");
            p.addValue("fromTs", Timestamp.from(from.atStartOfDay(IST).toInstant()));
        }
        if (to != null) {
            where.append(" and d.created_at < :toTs");
            p.addValue("toTs", Timestamp.from(to.plusDays(1).atStartOfDay(IST).toInstant()));
        }
        Search s = search(q, p);
        if (s != null) {
            where.append(" and (lower(coalesce(m.umrn, '')) like :like or lower(coalesce(d.utr, '')) like :like");
            s.orCustomer(where, "m.customer_id");
            if (s.number != null) {
                where.append(" or d.loan_id = :num or m.customer_id = :num or d.id = :num");
            }
            where.append(")");
        }
        String from_ = " from mandate_debit d join mandate m on m.id = d.mandate_id" + where;
        Map<String, Object> agg = jdbc.queryForMap("select count(*) n, "
                + "count(*) filter (where d.status = 'SUCCEEDED') ok_n, "
                + "coalesce(sum(d.amount_paise) filter (where d.status = 'SUCCEEDED'), 0) ok_amt" + from_, p);
        p.addValue("limit", sz).addValue("offset", (long) pg * sz);
        List<DebitRow> rows = withPeople(jdbc.query(DEBIT_SELECT + where
                + " order by d.id desc limit :limit offset :offset", p, MandateDashboardService::debitRow));
        return new DebitPage(rows, num(agg, "n"), pg, sz, num(agg, "ok_n"), num(agg, "ok_amt"));
    }

    /** Staff who have presented at least one debit — the "presented by" filter. */
    public List<Actor> actors() {
        requireAllowed();
        return jdbc.query("""
                select s.id, s.name, s.role from staff_user s
                where s.id in (select distinct actor_id from mandate_debit where actor_id is not null)
                order by s.name
                """, Map.of(), (rs, i) -> new Actor(rs.getLong("id"), rs.getString("name"), rs.getString("role")));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Joins only by primary key (mandate, staff_user); the customer is filled in afterwards by {@link #people}. */
    private static final String DEBIT_SELECT = """
            select d.id, d.mandate_id, m.umrn, d.loan_id, m.customer_id, d.attempt_no, d.amount_paise, d.status,
                   d.digio_status, d.failure_code, d.failure_reason, d.utr, d.settlement_date, d.triggered_by,
                   d.actor_id, s.name actor_name, s.role actor_role, d.dpd_at_attempt, d.present_count,
                   d.overpayment, d.digio_txn_id, d.created_at, d.updated_at, d.updated_by
            from mandate_debit d
            join mandate m on m.id = d.mandate_id
            left join staff_user s on s.id = d.actor_id""";

    private static DebitRow debitRow(ResultSet rs, int i) throws SQLException {
        Integer dpd = rs.getObject("dpd_at_attempt") == null ? null : rs.getInt("dpd_at_attempt");
        java.sql.Date settle = rs.getDate("settlement_date");
        return new DebitRow(rs.getLong("id"), rs.getLong("mandate_id"), rs.getString("umrn"), rs.getLong("loan_id"),
                rs.getLong("customer_id"), null, null, null, rs.getInt("attempt_no"), rs.getLong("amount_paise"),
                rs.getString("status"), rs.getString("digio_status"), rs.getString("failure_code"),
                rs.getString("failure_reason"), rs.getString("utr"), settle == null ? null : settle.toLocalDate(),
                rs.getString("triggered_by"), longOrNull(rs, "actor_id"), rs.getString("actor_name"),
                rs.getString("actor_role"), dpd, rs.getInt("present_count"), rs.getBoolean("overpayment"),
                rs.getString("digio_txn_id"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("updated_by"));
    }

    private List<DebitRow> withPeople(List<DebitRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, Person> people = people(rows.stream().map(DebitRow::customerId).distinct().toList());
        return rows.stream().map(d -> {
            Person who = people.getOrDefault(d.customerId(), Person.NONE);
            return new DebitRow(d.id(), d.mandateId(), d.umrn(), d.loanId(), d.customerId(), who.name(),
                    who.mobile(), who.applicationId(), d.attemptNo(), d.amountPaise(), d.status(), d.digioStatus(),
                    d.failureCode(), d.failureReason(), d.utr(), d.settlementDate(), d.trigger(), d.actorId(),
                    d.actorName(), d.actorRole(), d.dpdAtAttempt(), d.presentCount(), d.overpayment(),
                    d.digioTxnId(), d.createdAt(), d.updatedAt(), d.updatedBy());
        }).toList();
    }

    /**
     * Name, mobile and newest application for the given customers only — two indexed lookups on
     * {@code loan_application(customer_id)}, bounded by the page size.
     */
    private Map<Long, Person> people(List<Long> customerIds) {
        Map<Long, Person> out = new HashMap<>();
        if (customerIds.isEmpty()) {
            return out;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", customerIds);
        Map<Long, String[]> names = new HashMap<>();
        // The newest application that carries a profile: a fresh reborrow draft may have none yet.
        jdbc.query("""
                select distinct on (la.customer_id) la.customer_id, cp.full_name, cp.mobile
                from loan_application la join customer_profile cp on cp.application_id = la.id
                where la.customer_id in (:ids)
                order by la.customer_id, la.id desc
                """, p, rs -> {
            names.put(rs.getLong("customer_id"), new String[] {rs.getString("full_name"), rs.getString("mobile")});
        });
        jdbc.query("""
                select distinct on (customer_id) customer_id, id, status, loan_id
                from loan_application where customer_id in (:ids)
                order by customer_id, id desc
                """, p, rs -> {
            long cid = rs.getLong("customer_id");
            String[] n = names.getOrDefault(cid, new String[2]);
            out.put(cid, new Person(n[0], n[1], rs.getLong("id"), rs.getString("status"), longOrNull(rs, "loan_id")));
        });
        return out;
    }

    /** A parsed search term: a LIKE pattern, an optional number, and the mandate customers it names. */
    private final class Search {
        Long number;
        List<Long> customers = List.of();

        void orCustomer(StringBuilder where, String column) {
            if (!customers.isEmpty()) {
                where.append(" or ").append(column).append(" in (:custIds)");
            }
        }
    }

    /**
     * Resolves name / mobile to customer ids among customers that HOLD a mandate (bounded by the small
     * mandate table), so neither list ever scans customer_profile for the whole book.
     */
    private Search search(String q, MapSqlParameterSource p) {
        String term = q == null ? "" : q.trim();
        if (term.isEmpty()) {
            return null;
        }
        Search s = new Search();
        p.addValue("like", "%" + term.toLowerCase(Locale.ROOT) + "%");
        String digits = term.replaceAll("[\\s+-]", "");
        boolean numeric = digits.matches("\\d{1,18}");
        if (numeric) {
            s.number = Long.parseLong(digits);
            p.addValue("num", s.number);
        }
        MapSqlParameterSource sp = new MapSqlParameterSource("like", "%" + term.toLowerCase(Locale.ROOT) + "%")
                .addValue("mobile", numeric ? "%" + digits + "%" : null);
        s.customers = jdbc.queryForList("""
                select distinct la.customer_id
                from loan_application la join customer_profile cp on cp.application_id = la.id
                where la.customer_id in (select distinct customer_id from mandate)
                  and (lower(cp.full_name) like :like or (cast(:mobile as text) is not null and cp.mobile like :mobile))
                """, sp, Long.class);
        if (!s.customers.isEmpty()) {
            p.addValue("custIds", s.customers);
        }
        return s;
    }

    private static String mandateStatusClause(String status) {
        return switch (upper(status)) {
            case "ACTIVE" -> " and m.status = 'ACTIVE' and m.cancelled_at is null";
            case "PENDING" -> " and m.status = 'PENDING_AUTHORIZATION' and m.cancelled_at is null";
            case "FAILED" -> " and m.status in ('AUTHORIZATION_FAILED', 'EXPIRED') and m.cancelled_at is null";
            case "CANCELLED" -> " and m.cancelled_at is not null";
            case "BANK_UNSUPPORTED" -> " and m.status = 'BANK_UNSUPPORTED'";
            default -> "";
        };
    }

    static void requireAllowed() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (role == null || !ALLOWED.contains(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role COLLECTION_HEAD or ADMIN");
        }
    }

    private static String upper(String s) {
        return s == null ? "ALL" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static int pageSize(int size) {
        return size <= 0 ? 25 : Math.min(size, MAX_PAGE_SIZE);
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
