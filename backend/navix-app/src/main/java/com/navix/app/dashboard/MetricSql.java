package com.navix.app.dashboard;

import java.util.Set;

/**
 * The single definition of every countable dashboard metric.
 *
 * <p>Each {@link Metric} owns ONE SQL fragment returning
 * {@code (application_id, loan_id, segment, amount, grp, extra)} under the scope and the period
 * ({@code :from}/{@code :to}). Both the card aggregates and {@code GET /records} are built from that
 * fragment -- no metric has a second hand-written WHERE -- so the drawer's total can never disagree
 * with the number that was clicked.
 *
 * <ul>
 *   <li>{@code segment} -- FRESH or RELOAN, decided once in {@link #SEG}.</li>
 *   <li>{@code amount} -- the metric's own money in paise (principal, sanctioned, repayable...), or null.</li>
 *   <li>{@code grp} -- the metric's key (a day, a month, a bucket, a state, a staff id...) for metrics
 *       that group; the {@code :key} parameter filters on it.</li>
 *   <li>{@code extra} -- a secondary label (the state of a pincode).</li>
 * </ul>
 *
 * <p>Fragments use two scope placeholders, resolved per request: {@code %APP%} (credit views are
 * limited to files assigned to / sanctioned by the roster) and {@code %LOAN%} (collection views are
 * limited to loans on the roster's collection cases). Every other view, ADMIN included, sees the
 * whole book.
 */
public final class MetricSql {

    private MetricSql() {
    }

    /** IST calendar date of a timestamptz column -- every period filter in the dashboard is IST. */
    static String ist(String column) {
        return "cast(" + column + " at time zone 'Asia/Kolkata' as date)";
    }

    /**
     * Every non-draft application with its Fresh/Re-loan segment. Re-loan = the same customer has
     * another application whose loan was CLOSED on or before this application's creation date; all
     * else is Fresh. This is the ONLY place that rule lives.
     */
    static final String SEG = """
            with seg as (
                select a.id as application_id, a.customer_id, a.status, a.loan_id,
                       a.assigned_executive_id, a.sanctioned_by, a.amount_requested, a.sanctioned_amount_paise,
                       a.created_at,
                       cast(a.created_at at time zone 'Asia/Kolkata' as date) as created_on,
                       case when exists (
                                select 1 from loan_application b
                                join loan bl on bl.id = b.loan_id
                                where b.customer_id = a.customer_id and b.id <> a.id
                                  and bl.status = 'CLOSED'
                                  and bl.closed_on <= cast(a.created_at at time zone 'Asia/Kolkata' as date))
                            then 'RELOAN' else 'FRESH' end as segment
                from loan_application a
                where a.status <> 'DRAFT'
            )
            """;

    private static final String NIL = "cast(null as text)";
    private static final String NIL_AMOUNT = "cast(null as bigint)";
    private static final String REQUESTED = "coalesce(a.amount_requested, a.sanctioned_amount_paise)";
    private static final String LOAN_JOIN = "from seg a join loan l on l.id = a.loan_id ";

    private static final String VERIFIED =
            "(select cast(coalesce(sum(p.amount), 0) as bigint) from payment p"
                    + " where p.loan_id = l.id and p.status = 'VERIFIED')";
    static final String SETTLED_EXISTS = "exists (select 1 from settlement s"
            + " join collection_case sc on sc.id = s.collection_case_id"
            + " where sc.loan_id = l.id and s.status = 'APPROVED')";

    /** Address-derived geography: ADDRESS, then AADHAAR, then PAN. One row per (application, check type). */
    private static final String GEO = " left join application_verification va on va.application_id = a.application_id"
            + " and va.check_type = 'ADDRESS'"
            + " left join application_verification vb on vb.application_id = a.application_id"
            + " and vb.check_type = 'AADHAAR'"
            + " left join application_verification vc on vc.application_id = a.application_id"
            + " and vc.check_type = 'PAN' ";
    static final String STATE_SQL = "coalesce(initcap(lower(coalesce("
            + "nullif(btrim(va.derived->>'state'), ''), nullif(btrim(vb.derived->>'state'), ''),"
            + " nullif(btrim(vc.derived->>'addressState'), '')))), 'Unknown')";
    private static final String PIN_SQL = "coalesce(nullif(btrim(va.derived->>'pincode'), ''),"
            + " nullif(btrim(vb.derived->>'pincode'), ''), nullif(btrim(vc.derived->>'addressZip'), ''), 'Unknown')";

    /**
     * Credit assignment events with the staffer they name and the IST day. ASSIGN notes read
     * {@code executiveId=N}; REASSIGN reads {@code previousExecutiveId=P newExecutiveId=N}.
     */
    static final String ASSIGN_EVENTS = "select e.application_id,"
            + " cast(substring(e.notes from '(?:newExecutiveId=|^executiveId=)([0-9]+)') as bigint) as staff_id,"
            + " " + ist("e.at") + " as day"
            + " from application_event e where e.action in ('ASSIGN', 'REASSIGN')";

    private static final String BUCKET = "case when (cast(:to as date) - l.due_date) <= 0 then 'RUNNING'"
            + " when (cast(:to as date) - l.due_date) <= 30 then 'D1_30'"
            + " when (cast(:to as date) - l.due_date) <= 60 then 'D31_60'"
            + " when (cast(:to as date) - l.due_date) <= 90 then 'D61_90' else 'D90_PLUS' end";

    private static String sel(String amount, String grp, String extra) {
        return "select a.application_id, a.loan_id, a.segment, " + amount + " as amount, "
                + grp + " as grp, " + extra + " as extra ";
    }

    private static final Set<String> ADMIN_ONLY = Set.of("ADMIN");
    private static final Set<String> ADMIN_CREDIT = Set.of("ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE");
    private static final Set<String> ADMIN_CREDIT_DISB =
            Set.of("ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE", "DISBURSEMENT_HEAD");
    private static final Set<String> ADMIN_COLLECTION = Set.of("ADMIN", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE");
    private static final Set<String> ADMIN_COLLECTION_HEAD = Set.of("ADMIN", "COLLECTION_HEAD");
    private static final Set<String> ADMIN_DISBURSEMENT = Set.of("ADMIN", "DISBURSEMENT_HEAD");

    /** How a record key narrows the period, so a drill-down on one day/month looks at exactly that. */
    enum KeyKind { NONE, DAY, MONTH, GROUP_DAY, OTHER }

    public enum Metric {
        // ---- applications ----
        APPLICATIONS(ADMIN_CREDIT, KeyKind.OTHER, false,
                sel(REQUESTED, "cast(a.created_on as text)", NIL)
                        + "from seg a where a.created_on between :from and :to %APP%"),
        DISBURSED(ADMIN_CREDIT_DISB, KeyKind.OTHER, false,
                sel("l.principal", "cast(l.disbursed_on as text)", NIL) + LOAN_JOIN
                        + "where l.disbursed_on between :from and :to %APP% %LOAN%"),
        PENDING(ADMIN_CREDIT, KeyKind.OTHER, false,
                sel(REQUESTED, "cast(a.created_on as text)", NIL)
                        + "from seg a where a.created_on between :from and :to %APP%"
                        + " and a.status in ('KYC_PENDING', 'KYC_APPROVED', 'CREDIT_EXEC_PENDING', 'SANCTIONED',"
                        + " 'DISBURSEMENT_PENDING', 'PRE_APPROVED', 'DISBURSEMENT_FAILED')"),
        REJECTED(ADMIN_CREDIT, KeyKind.OTHER, false,
                sel(REQUESTED, "cast(a.created_on as text)", NIL)
                        + "from seg a where a.created_on between :from and :to %APP%"
                        + " and a.status in ('REJECTED', 'KYC_REJECTED')"),
        PENDING_SANCTIONED(ADMIN_CREDIT, KeyKind.OTHER, false,
                sel("a.sanctioned_amount_paise", NIL, NIL)
                        + "from seg a where a.created_on between :from and :to %APP% and a.status = 'SANCTIONED'"),
        PENDING_DISBURSAL(ADMIN_CREDIT_DISB, KeyKind.OTHER, false,
                sel(REQUESTED, NIL, NIL)
                        + "from seg a where a.created_on between :from and :to %APP%"
                        + " and a.status = 'DISBURSEMENT_PENDING'"),

        // ---- closures: CLOSED is every loan closed in the period; SETTLED is the subset closed by settlement ----
        CLOSED(ADMIN_COLLECTION, KeyKind.OTHER, false,
                sel("l.principal", "cast(l.closed_on as text)", NIL) + LOAN_JOIN
                        + "where l.status = 'CLOSED' and l.closed_on between :from and :to %LOAN%"),
        SETTLED(ADMIN_COLLECTION, KeyKind.OTHER, false,
                sel("l.principal", "cast(l.closed_on as text)", NIL) + LOAN_JOIN
                        + "where l.status = 'CLOSED' and l.closed_on between :from and :to %LOAN% and "
                        + SETTLED_EXISTS),
        PART_PAID(ADMIN_COLLECTION, KeyKind.OTHER, false,
                sel("l.principal", NIL, NIL) + LOAN_JOIN
                        + "where l.status <> 'CLOSED' %LOAN% and exists (select 1 from payment p"
                        + " where p.loan_id = l.id and p.status = 'VERIFIED' and p.partial"
                        + " and p.paid_on between :from and :to)"),

        // ---- pre-closure week: keyed by due date ----
        DUE_ON(ADMIN_COLLECTION, KeyKind.DAY, false,
                sel("l.total_repayable", "cast(l.due_date as text)", NIL) + LOAN_JOIN
                        + "where l.due_date between :from and :to %LOAN%"),
        PRECLOSED_ON(ADMIN_COLLECTION, KeyKind.DAY, false,
                sel(VERIFIED, "cast(l.due_date as text)", NIL) + LOAN_JOIN
                        + "where l.due_date between :from and :to %LOAN%"
                        + " and l.status = 'CLOSED' and l.closed_on < l.due_date"),
        PENDING_ON(ADMIN_COLLECTION, KeyKind.DAY, true,
                sel("l.total_repayable", "cast(l.due_date as text)", NIL) + LOAN_JOIN
                        + "where l.due_date between :from and :to %LOAN% and l.status <> 'CLOSED'"),
        RECEIVED_ON(ADMIN_COLLECTION, KeyKind.DAY, false,
                sel(VERIFIED, "cast(l.due_date as text)", NIL) + LOAN_JOIN
                        + "where l.due_date between :from and :to %LOAN% and l.status <> 'CLOSED'"
                        + " and " + VERIFIED + " > 0"),

        // ---- keyed metrics ----
        AUM_BUCKET(ADMIN_ONLY, KeyKind.OTHER, true,
                sel("l.principal", BUCKET, NIL) + LOAN_JOIN
                        + "where l.disbursed_on <= :to and (l.closed_on is null or l.closed_on > :to)"
                        + " and l.due_date is not null %LOAN%"),
        COLLECTION_MONTH(ADMIN_COLLECTION, KeyKind.MONTH, false,
                sel("l.total_repayable", "to_char(l.due_date, 'YYYY-MM')", NIL) + LOAN_JOIN
                        + "where l.due_date between :from and :to %LOAN%"),
        COLLECTION_GROUP(ADMIN_COLLECTION_HEAD, KeyKind.GROUP_DAY, false,
                sel("l.total_repayable",
                        "case when cast(:groupBy as text) = 'STATE' then 'STATE:' || " + STATE_SQL
                                + " else 'EXEC:' || coalesce(cast(a.assigned_executive_id as text), 'NONE') end"
                                + " || ':' || cast(l.due_date as text)", NIL)
                        + LOAN_JOIN + GEO + "where l.due_date between :from and :to %LOAN%"),
        RELOAN_RETENTION(ADMIN_COLLECTION, KeyKind.OTHER, false,
                "select r.application_id, r.loan_id, r.segment, r.principal as amount, g.grp as grp, "
                        + NIL + " as extra from (select a.application_id, a.loan_id, a.segment, l.principal,"
                        + " (l.status = 'CLOSED') as closed, (l.status = 'CLOSED' and exists ("
                        + "select 1 from loan_application b where b.customer_id = a.customer_id and b.id <> a.application_id"
                        + " and b.status <> 'DRAFT' and " + ist("b.created_at") + " >= l.closed_on)) as reloaned "
                        + LOAN_JOIN + "where l.due_date between :from and :to %LOAN%) r"
                        + " cross join lateral (values ('DUE', true), ('CLOSED', r.closed), ('RELOAN', r.reloaned),"
                        + " ('NO_REPEAT', r.closed and not r.reloaned)) as g(grp, ok) where g.ok"),
        STATE(ADMIN_ONLY, KeyKind.OTHER, false,
                sel("l.principal", STATE_SQL, NIL) + LOAN_JOIN + GEO
                        + "where l.disbursed_on between :from and :to %LOAN%"),
        PINCODE(ADMIN_ONLY, KeyKind.OTHER, false,
                sel("l.principal", PIN_SQL, STATE_SQL) + LOAN_JOIN + GEO
                        + "where l.disbursed_on between :from and :to %LOAN%"),
        COMPANY(ADMIN_ONLY, KeyKind.OTHER, false,
                sel("l.principal", "coalesce(nullif(lower(btrim(cp.employer)), ''), 'unknown')", NIL) + LOAN_JOIN
                        + "left join customer_profile cp on cp.application_id = a.application_id"
                        + " where l.disbursed_on between :from and :to %LOAN%"),

        // ---- calendar ----
        CALENDAR_DUE(ADMIN_ONLY, KeyKind.DAY, false,
                sel("l.total_repayable", "cast(l.due_date as text)", NIL) + LOAN_JOIN
                        + "where l.due_date between :from and :to %LOAN%"),
        CALENDAR_DISBURSED(ADMIN_ONLY, KeyKind.DAY, false,
                sel("l.principal", "cast(l.disbursed_on as text)", NIL) + LOAN_JOIN
                        + "where l.disbursed_on between :from and :to %LOAN%"),
        CALENDAR_BIRTHDAY(ADMIN_ONLY, KeyKind.DAY, false,
                "select b.application_id, b.loan_id, b.segment, " + NIL_AMOUNT + " as amount,"
                        + " cast(cast(d.day as date) as text) as grp, " + NIL + " as extra"
                        + " from (select distinct on (a.customer_id) a.application_id, a.loan_id, a.segment, cp.dob "
                        + LOAN_JOIN + "join customer_profile cp on cp.application_id = a.application_id"
                        + " where cp.dob is not null %LOAN% order by a.customer_id, l.id desc) b"
                        + " join generate_series(cast(:from as date), cast(:to as date), interval '1 day') d(day)"
                        + " on to_char(b.dob, 'MM-DD') = to_char(d.day, 'MM-DD')"),

        // ---- staff ----
        STAFF_FILES(ADMIN_CREDIT, KeyKind.OTHER, false,
                sel(REQUESTED, "cast(x.staff_id as text)", NIL)
                        + "from (select distinct ev.application_id, ev.staff_id from (" + ASSIGN_EVENTS + ") ev"
                        + " where ev.staff_id is not null and ev.day between :from and :to) x"
                        + " join seg a on a.application_id = x.application_id"
                        + " where (:allStaff or x.staff_id in (:ids))"),
        STAFF_CASES(ADMIN_COLLECTION, KeyKind.OTHER, false,
                sel("l.principal", "cast(cc.assigned_officer_id as text)", NIL)
                        + "from collection_case cc join loan l on l.id = cc.loan_id"
                        + " join seg a on a.loan_id = l.id"
                        + " where cc.assigned_officer_id is not null and " + ist("cc.created_at")
                        + " between :from and :to and (:allStaff or cc.assigned_officer_id in (:ids))"),

        // ---- PF / ROI tables: keyed by rate ----
        PF_RATE(ADMIN_DISBURSEMENT, KeyKind.OTHER, false,
                sel("l.principal", "cast(trim_scale(round(l.processing_fee * 100.0 / l.principal, 2)) as text)", NIL)
                        + LOAN_JOIN + "where l.disbursed_on between :from and :to %LOAN%"
                        + " and l.principal > 0 and l.processing_fee is not null"),
        ROI_RATE(ADMIN_DISBURSEMENT, KeyKind.OTHER, false,
                sel("l.principal", "cast(trim_scale(round(l.daily_interest_rate * 100, 2)) as text)", NIL)
                        + LOAN_JOIN + "where l.disbursed_on between :from and :to %LOAN%"
                        + " and l.daily_interest_rate is not null");

        private final Set<String> views;
        private final KeyKind keyKind;
        private final boolean owedSum;
        private final String template;

        Metric(Set<String> views, KeyKind keyKind, boolean owedSum, String template) {
            this.views = views;
            this.keyKind = keyKind;
            this.owedSum = owedSum;
            this.template = template;
        }

        /** Views that may read this metric (and therefore its records). */
        public Set<String> views() {
            return views;
        }

        KeyKind keyKind() {
            return keyKind;
        }

        /** True when the drawer's money total is what is still owed (computed by RepaymentService), not an SQL sum. */
        boolean owedSum() {
            return owedSum;
        }

        /** The fragment with the request's scope resolved. */
        String sql(DashboardScope scope) {
            return template.replace("%APP%", appScope(scope)).replace("%LOAN%", loanScope(scope));
        }
    }

    /** Credit views see only files assigned to, or sanctioned by, their roster. */
    private static String appScope(DashboardScope scope) {
        if (scope.orgWide() || !scope.view().startsWith("CREDIT_")) return "";
        return " and (a.assigned_executive_id in (:ids) or a.sanctioned_by in (:ids))";
    }

    /** Collection views see only loans that sit on a collection case assigned to their roster. */
    private static String loanScope(DashboardScope scope) {
        if (scope.orgWide() || !scope.view().startsWith("COLLECTION_")) return "";
        return " and exists (select 1 from collection_case cc where cc.loan_id = l.id"
                + " and cc.assigned_officer_id in (:ids))";
    }

    /**
     * {@code body} with {@code {F}} replaced by the metric's fragment (already narrowed by
     * {@code :key} on its group), prefixed with the {@link #SEG} CTE. The one way to build a query
     * over a metric.
     */
    static String query(Metric metric, DashboardScope scope, String body) {
        String f = "(select x.* from (" + metric.sql(scope) + ") x"
                + " where (cast(:key as text) is null or x.grp = :key)) f";
        return SEG + body.replace("{F}", f);
    }
}
