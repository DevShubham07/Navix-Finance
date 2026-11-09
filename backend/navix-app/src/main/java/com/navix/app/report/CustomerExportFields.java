package com.navix.app.report;

import com.navix.loan.service.HandoverDerivedKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The allowlist behind the admin customer-data export: every exportable column, as a key plus the
 * SQL text that produces it. A request only ever names keys; the SQL comes from here and nowhere
 * else, so an unknown key is refused and nothing user-supplied reaches a statement.
 *
 * <p><b>Never exportable</b> (there is deliberately no field for any of these): credentials and reset
 * tokens, password hashes, {@code digilocker_client_id}, {@code credit_brief_facts}, raw provider
 * JSON ({@code raw_response}, {@code request_json}, {@code response_json}), bureau report URLs,
 * document bytes, S3 keys, document passwords, loan-document storage URIs, mandate authentication
 * URLs and {@code profile_change_log} values. {@code CustomerExportFieldsTest} scans every
 * expression for those names.
 *
 * <p>Expressions are written over fixed aliases: {@code a} the application the row is built on,
 * {@code p} its profile, {@code l} the loan, plus the lateral joins in {@link #JOINS}. In customer
 * grain {@code a} is the customer's latest application and {@code l} their latest loan; in
 * application grain {@code a} is that application and {@code l} its own loan.
 */
public final class CustomerExportFields {

    public enum Type { TEXT, TEXTNUM, NUMBER, MONEY, DATE, DATETIME, BOOL }

    public enum Grain { CUSTOMER, APPLICATION }

    /** Figures that cannot come from SQL: the live balance and DPD number come from RepaymentService. */
    public enum Live { NONE, OUTSTANDING, DPD_DAYS }

    public record Field(String key, String label, String group, Type type, boolean sensitive, String sql,
                        Set<String> joins, Live live) {
    }

    private record JoinDef(String customerSql, String applicationSql, Set<String> deps) {
    }

    static final String TODAY = "(now() at time zone 'Asia/Kolkata')::date";

    public static final String G_PERSONAL = "Personal";
    public static final String G_ID = "ID numbers";
    public static final String G_EMPLOYMENT = "Employment and salary";
    public static final String G_APPLICATION = "Applications";
    public static final String G_BANK = "Bank and payout";
    public static final String G_LOANS = "Loans";
    public static final String G_REPAYMENTS = "Repayments";
    public static final String G_VERIFICATION = "Verification and KYC";
    public static final String G_BUREAU = "Credit bureau";
    public static final String G_REFERENCES = "References";
    public static final String G_MANDATES = "Mandates and debits";
    public static final String G_COLLECTIONS = "Collections";
    public static final String G_CALLS = "Calls, notes and owners";
    public static final String G_DOCUMENTS = "Documents and consent";
    public static final String G_REFERRAL = "Referral and lead source";

    public static final List<String> GROUPS = List.of(G_PERSONAL, G_ID, G_EMPLOYMENT, G_APPLICATION, G_BANK,
            G_LOANS, G_REPAYMENTS, G_VERIFICATION, G_BUREAU, G_REFERENCES, G_MANDATES, G_COLLECTIONS, G_CALLS,
            G_DOCUMENTS, G_REFERRAL);

    /** Join name to SQL, in an order where every join only refers to ones before it. */
    static final Map<String, JoinDef> JOINS = new LinkedHashMap<>();

    private static final List<Field> FIELDS = new ArrayList<>();
    private static final Map<String, Field> BY_KEY = new LinkedHashMap<>();

    /** Verification check types, as application_verification.check_type spells them. */
    static final Map<String, String> CHECKS = new LinkedHashMap<>();

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z]+");

    private CustomerExportFields() {
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static String ts(String x) {
        return "to_char(" + x + " at time zone 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI')";
    }

    private static String d(String x) {
        return "to_char(" + x + ", 'YYYY-MM-DD')";
    }

    private static String staff(String idExpr) {
        return "(select s.name from staff_user s where s.id = " + idExpr + ")";
    }

    private static String rs(String paise) {
        return "('Rs.' || to_char((" + paise + ") / 100.0, 'FM999999999990.00'))";
    }

    /**
     * One cell summarising a history: newest first, at most {@code limit} entries, never more than
     * 3,000 characters. {@code item} is built with concat_ws so a null part simply drops out.
     */
    private static String hist(String item, String from, String order, int limit) {
        return "(select left(string_agg(h.s, ' | ' order by h.o desc), 3000) from (select " + order + " as o, "
                + item + " as s from " + from + " order by " + order + " desc limit " + limit + ") h)";
    }

    private static String bucket(String loanAlias) {
        String days = TODAY + " - " + loanAlias + ".due_date";
        return "case when " + loanAlias + ".status in ('ACTIVE', 'OVERDUE', 'IN_COLLECTIONS') and " + loanAlias
                + ".due_date is not null then case when " + days + " <= 0 then 'Not yet due' when " + days
                + " <= 7 then '1-7 days' when " + days + " <= 30 then '8-30 days' when " + days
                + " <= 60 then '31-60 days' when " + days + " <= 90 then '61-90 days' else '90+ days' end end";
    }

    private static Field add(String key, String label, String group, Type type, boolean sensitive, String sql,
                             Live live, String... joins) {
        Set<String> js = new LinkedHashSet<>(List.of(joins));
        Field f = new Field(key, label, group, type, sensitive, sql, Collections.unmodifiableSet(js), live);
        if (BY_KEY.put(key, f) != null) {
            throw new IllegalStateException("duplicate export field " + key);
        }
        FIELDS.add(f);
        return f;
    }

    private static void f(String key, String label, String group, Type type, String sql, String... joins) {
        add(key, label, group, type, false, sql, Live.NONE, joins);
    }

    /** An ID-number field: full value, only ever served when the request ticks "include full ID numbers". */
    private static void id(String key, String label, String group, Type type, String sql, String... joins) {
        add(key, label, group, type, true, sql, Live.NONE, joins);
    }

    private static void join(String name, String customerSql, String applicationSql, String... deps) {
        JOINS.put(name, new JoinDef(customerSql, applicationSql, Set.of(deps)));
    }

    private static String latest(String table, String alias, String where, String order) {
        return "left join lateral (select * from " + table + " x where " + where + " order by " + order
                + " desc limit 1) " + alias + " on true";
    }

    // ── the catalog ──────────────────────────────────────────────────────────────────────────

    static {
        // Joins. p and l first: the others hang off them.
        join("p", "left join customer_profile p on p.application_id = a.id",
                "left join customer_profile p on p.application_id = a.id");
        join("l", "left join lateral (select * from loan x where x.customer_id = a.customer_id order by x.id desc limit 1) l on true",
                "left join loan l on l.id = a.loan_id");
        String onLoan = "x.loan_id = l.id";
        String lp = latest("payment", "lp", onLoan, "x.id");
        join("lp", lp, lp, "l");
        String cc = latest("collection_case", "cc", onLoan, "x.created_at");
        join("cc", cc, cc, "l");
        String md = latest("mandate_debit", "md", onLoan, "x.id");
        join("md", md, md, "l");
        // Customer grain looks across all of the customer's applications, application grain at this one.
        join("m", latest("mandate", "m", "x.customer_id = a.customer_id", "x.id"),
                latest("mandate", "m", "x.loan_application_id = a.id", "x.id"));
        join("rj", latest("application_rejection", "rj", "x.customer_id = a.customer_id", "x.id"),
                latest("application_rejection", "rj", "x.application_id = a.id", "x.id"));

        CHECKS.put("PAN", "PAN");
        CHECKS.put("AADHAAR", "Aadhaar");
        CHECKS.put("DIGILOCKER", "DigiLocker");
        CHECKS.put("ADDRESS", "Address");
        CHECKS.put("EMPLOYMENT", "Employment (EPFO)");
        CHECKS.put("EMAIL", "Official email");
        CHECKS.put("SALARY", "Salary");
        CHECKS.put("PENNY_DROP", "Penny drop");
        CHECKS.put("SELFIE", "Selfie");
        CHECKS.put("ESIGN", "eSign");
        CHECKS.put("BUREAU", "Bureau");
        for (String c : CHECKS.keySet()) {
            String sql = "left join lateral (select * from application_verification x where x.application_id = a.id"
                    + " and x.check_type = '" + c + "' order by x.id desc limit 1) " + vAlias(c) + " on true";
            join(vAlias(c), sql, sql);
        }

        // Personal (customer_profile)
        String P = G_PERSONAL;
        f("customer_id", "Customer ID", P, Type.NUMBER, "a.customer_id");
        f("full_name", "Full name", P, Type.TEXT, "p.full_name", "p");
        f("mobile", "Mobile", P, Type.TEXTNUM, "p.mobile", "p");
        f("dob", "Date of birth", P, Type.DATE, d("p.dob"), "p");
        f("email", "Personal email", P, Type.TEXT, "p.email", "p");
        f("official_email", "Official email", P, Type.TEXT, "p.official_email", "p");
        f("address", "Address", P, Type.TEXT, "p.address", "p");
        f("emergency_contact_name", "Emergency contact name", P, Type.TEXT, "p.emergency_contact_name", "p");
        f("emergency_contact_phone", "Emergency contact phone", P, Type.TEXTNUM, "p.emergency_contact_phone", "p");
        f("emergency_contact_relation", "Emergency contact relation", P, Type.TEXT, "p.emergency_contact_relation", "p");
        f("joined_on", "Joined on (first application)", P, Type.DATE,
                "(select " + d("min(x.created_at) at time zone 'Asia/Kolkata'") + " from loan_application x where x.customer_id = a.customer_id)");
        f("terms_version", "Terms version", P, Type.TEXT, "p.terms_version", "p");
        f("terms_accepted_at", "Terms accepted at", P, Type.DATETIME, ts("p.terms_accepted_at"), "p");
        f("pep_declared_at", "PEP declared at", P, Type.DATETIME, ts("p.pep_declared_at"), "p");
        f("agreement_accepted", "Agreement accepted", P, Type.BOOL, "p.agreement_accepted", "p");
        f("email_verified", "Email verified", P, Type.BOOL, "p.email_verified", "p");
        f("personal_email_verified", "Personal email verified", P, Type.BOOL, "p.personal_email_verified", "p");
        f("official_email_otp_verified", "Official email OTP verified", P, Type.BOOL, "p.official_email_otp_verified", "p");
        f("address_verified", "Address verified", P, Type.BOOL, "p.address_verified", "p");

        // ID numbers (all sensitive)
        String I = G_ID;
        id("aadhaar", "Aadhaar number", I, Type.TEXTNUM, "p.aadhaar", "p");
        id("pan", "PAN", I, Type.TEXT, "p.pan", "p");
        id("uan", "UAN", I, Type.TEXTNUM, "p.uan", "p");
        id("salary_account_number", "Salary account number", I, Type.TEXTNUM, "p.salary_account_number", "p");
        id("disbursal_account_number", "Disbursal account number", I, Type.TEXTNUM, "a.disbursal_account_number");
        id("penny_drop_account_number", "Penny-drop account number", I, Type.TEXTNUM,
                "(select x.account_number from penny_drop_attempt x where x.application_id = a.id order by x.id desc limit 1)");

        // Employment and salary
        String E = G_EMPLOYMENT;
        f("employer", "Employer", E, Type.TEXT, "p.employer", "p");
        f("employment_status", "Employment status", E, Type.TEXT, "p.employment_status", "p");
        f("monthly_salary", "Monthly salary", E, Type.MONEY, "p.monthly_salary_paise", "p");
        f("annual_salary", "Annual salary", E, Type.MONEY, "p.annual_salary_paise", "p");
        f("salary_percentage", "Salary percentage", E, Type.NUMBER, "p.salary_percentage", "p");
        f("increment_percentage", "Increment percentage", E, Type.NUMBER, "p.increment_percentage", "p");
        f("previous_salary_date", "Previous salary date", E, Type.DATE, d("p.previous_salary_date"), "p");
        f("salary_credit_day", "Salary credit day", E, Type.NUMBER, "a.salary_credit_day");
        f("salary_bank", "Salary bank", E, Type.TEXT, "p.salary_bank", "p");
        f("salary_ifsc", "Salary account IFSC", E, Type.TEXT, "p.salary_ifsc", "p");
        f("salary_account_mobile", "Salary account mobile", E, Type.TEXTNUM, "p.salary_account_mobile", "p");

        // Application
        String A = G_APPLICATION;
        f("application_id", "Application ID", A, Type.NUMBER, "a.id");
        f("application_created_at", "Application created at", A, Type.DATETIME, ts("a.created_at"));
        f("application_status", "Application status", A, Type.TEXT, "a.status");
        f("journey_step", "Journey step", A, Type.TEXT, "a.journey_step");
        f("amount_requested", "Amount requested", A, Type.MONEY, "a.amount_requested");
        f("eligible_limit", "Eligible limit", A, Type.MONEY, "a.eligible_limit");
        f("purpose", "Purpose", A, Type.TEXT, "a.purpose");
        f("sanctioned_amount", "Sanctioned amount", A, Type.MONEY, "a.sanctioned_amount_paise");
        f("approved_repayment_date", "Approved repayment date", A, Type.DATE, d("a.approved_repayment_date"));
        f("sanction_tenure_days", "Sanction tenure (days)", A, Type.NUMBER, "a.sanction_tenure_days");
        f("sanction_remarks", "Sanction remarks", A, Type.TEXT, "a.sanction_remarks");
        f("sanctioned_by", "Sanctioned by", A, Type.TEXT, staff("a.sanctioned_by"));
        f("sanctioned_at", "Sanctioned at", A, Type.DATETIME, ts("a.sanctioned_at"));
        f("marked_pending_at", "Marked pending at", A, Type.DATETIME, ts("a.marked_pending_at"));
        f("pending_reason", "Pending reason", A, Type.TEXT, "a.pending_reason");
        f("assigned_executive", "Assigned credit executive", A, Type.TEXT, staff("a.assigned_executive_id"));
        f("reapplied_from", "Reapplied from application", A, Type.NUMBER, "a.reapplied_from");
        f("stage_date", "Current stage since", A, Type.DATETIME,
                "(select " + ts("max(e.at)") + " from application_event e where e.application_id = a.id"
                        + " and e.to_status = a.status and (e.from_status is null or e.from_status <> e.to_status))");
        f("applications_count", "Number of applications", A, Type.NUMBER,
                "(select count(*) from loan_application x where x.customer_id = a.customer_id)");
        f("application_history", "Application history (summary)", A, Type.TEXT,
                hist("concat_ws(' ', '#' || x.id, x.status, " + d("x.created_at at time zone 'Asia/Kolkata'") + ")",
                        "loan_application x where x.customer_id = a.customer_id", "x.id", 20));

        // Bank and payout
        String B = G_BANK;
        f("disbursal_ifsc", "Disbursal IFSC", B, Type.TEXT, "a.disbursal_ifsc");
        f("disbursal_holder_name", "Disbursal account holder", B, Type.TEXT, "a.disbursal_holder_name");
        f("disbursal_bank", "Disbursal bank", B, Type.TEXT, "a.disbursal_bank");
        f("disbursal_account_changed", "Disbursal account changed", B, Type.BOOL, "a.disbursal_account_changed");
        f("disbursal_account_verified", "Disbursal account verified", B, Type.BOOL, "a.disbursal_account_verified");
        f("disbursal_confirmed_at", "Disbursal account confirmed at", B, Type.DATETIME, ts("a.disbursal_confirmed_at"));
        f("penny_drop_verified", "Penny drop verified", B, Type.BOOL, "p.penny_drop_verified", "p");
        f("name_match_score", "Bank name match score", B, Type.NUMBER, "p.name_match_score", "p");
        f("penny_drop_attempts", "Penny-drop attempts", B, Type.NUMBER,
                "(select count(*) from penny_drop_attempt x where x.application_id = a.id)");
        f("penny_drop_last_failure", "Penny-drop last failure", B, Type.TEXT,
                "(select x.failure_reason from penny_drop_attempt x where x.application_id = a.id"
                        + " and x.succeeded is not true order by x.id desc limit 1)");

        // Loans
        String L = G_LOANS;
        f("loan_id", "Loan ID", L, Type.NUMBER, "l.id", "l");
        f("principal", "Principal", L, Type.MONEY, "l.principal", "l");
        f("processing_fee", "Processing fee", L, Type.MONEY, "l.processing_fee", "l");
        f("gst", "GST on fee", L, Type.MONEY, "l.gst", "l");
        f("net_disbursed", "Net disbursed", L, Type.MONEY, "l.net_disbursed", "l");
        f("daily_interest_rate", "Daily interest rate", L, Type.NUMBER, "l.daily_interest_rate", "l");
        f("disbursed_on", "Disbursed on", L, Type.DATE, d("l.disbursed_on"), "l");
        f("due_date", "Due date (salary day)", L, Type.DATE, d("l.due_date"), "l");
        f("grace_days", "Grace days", L, Type.NUMBER, "l.grace_days", "l");
        f("total_repayable", "Total repayable", L, Type.MONEY, "l.total_repayable", "l");
        add("outstanding_live", "Outstanding today (live)", L, Type.MONEY, false, "null", Live.OUTSTANDING, "l");
        f("loan_status", "Loan status", L, Type.TEXT,
                "case when l.status = 'ACTIVE' and l.due_date is not null and l.due_date < " + TODAY
                        + " then 'OVERDUE' else l.status end", "l");
        add("dpd_days", "Days past due", L, Type.NUMBER, false, "null", Live.DPD_DAYS, "l");
        f("dpd_bucket", "DPD bucket", L, Type.TEXT, bucket("l"), "l");
        f("disbursal_txn_ref", "Disbursal transaction ref", L, Type.TEXT, "l.disbursal_txn_ref", "l");
        f("closed_on", "Closed on", L, Type.DATE, d("l.closed_on"), "l");
        f("loan_count", "Number of loans", L, Type.NUMBER,
                "(select count(*) from loan x where x.customer_id = a.customer_id)");
        f("loan_history", "Earlier loans (summary)", L, Type.TEXT,
                hist("concat_ws(' ', '#' || x.id, " + rs("x.principal") + ", x.status, 'disbursed', "
                                + d("x.disbursed_on") + ", 'due', " + d("x.due_date") + ", 'closed', " + d("x.closed_on") + ")",
                        "loan x where x.customer_id = a.customer_id and x.id is distinct from l.id", "x.id", 20), "l");

        // Repayments
        String R = G_REPAYMENTS;
        f("paid_so_far", "Paid so far (verified)", R, Type.MONEY,
                "(select coalesce(sum(x.amount), 0) from payment x where x.loan_id = l.id and x.status = 'VERIFIED')", "l");
        f("last_payment_date", "Last payment date", R, Type.DATE, d("lp.paid_on"), "lp");
        f("last_payment_amount", "Last payment amount", R, Type.MONEY, "lp.amount", "lp");
        f("last_payment_method", "Last payment method", R, Type.TEXT, "lp.method", "lp");
        f("last_payment_status", "Last payment status", R, Type.TEXT, "lp.status", "lp");
        f("last_payment_ref", "Last payment reference", R, Type.TEXT, "lp.txn_ref", "lp");
        f("last_payment_rejection_reason", "Last payment rejection reason", R, Type.TEXT, "lp.rejection_reason", "lp");
        f("last_payment_rejection_note", "Last payment rejection note", R, Type.TEXT, "lp.rejection_note", "lp");
        f("payment_history", "Payment history (summary)", R, Type.TEXT,
                hist("concat_ws(' ', " + d("x.paid_on") + ", " + rs("x.amount") + ", x.method, x.status, x.txn_ref)",
                        "payment x where x.loan_id = l.id", "x.id", 30), "l");
        f("collection_payment_history", "Collections payments (summary)", R, Type.TEXT,
                hist("concat_ws(' ', " + d("x.paid_on") + ", x.kind, " + rs("x.amount_paise") + ", x.status, x.txn_ref)",
                        "collection_payment x where x.loan_id = l.id", "x.raised_at", 30), "l");

        // Verification and KYC
        String V = G_VERIFICATION;
        f("pan_verified", "PAN verified (profile flag)", V, Type.BOOL, "p.pan_verified", "p");
        f("aadhaar_linked", "Aadhaar linked to PAN", V, Type.BOOL, "p.aadhaar_linked", "p");
        f("aadhaar_verified", "Aadhaar verified", V, Type.BOOL, "p.aadhaar_verified", "p");
        Map<String, Set<String>> derivedKeys = HandoverDerivedKeys.byCheckType();
        for (Map.Entry<String, String> c : CHECKS.entrySet()) {
            String t = c.getKey();
            String v = vAlias(t);
            String k = "verif_" + t.toLowerCase();
            String lab = c.getValue() + " check: ";
            f(k + "_status", lab + "status", V, Type.TEXT, v + ".status", v);
            f(k + "_provider", lab + "provider", V, Type.TEXT, v + ".provider", v);
            f(k + "_name_match", lab + "name match", V, Type.NUMBER, v + ".name_match", v);
            f(k + "_score", lab + "score", V, Type.NUMBER, v + ".score", v);
            f(k + "_message", lab + "message", V, Type.TEXT, v + ".message", v);
            Set<String> keys = derivedKeys.get(t);
            if (keys != null && !keys.isEmpty()) {
                // The whitelist is the only thing that reaches this expression, and each key is checked
                // to be letters only, so it cannot carry SQL.
                List<String> quoted = new ArrayList<>();
                for (String key : new TreeSet<>(keys)) {
                    if (!SAFE_KEY.matcher(key).matches()) {
                        throw new IllegalStateException("unsafe derived key " + key);
                    }
                    quoted.add("'" + key + "'");
                }
                String sql = "(case when jsonb_typeof(" + v + ".derived) = 'object' then (select left(string_agg(e.key || '='"
                        + " || e.value, '; ' order by e.key), 2000) from jsonb_each_text(" + v + ".derived) e"
                        + " where e.key in (" + String.join(", ", quoted) + ")) end)";
                // The penny-drop and EPFO details carry the full account number / UAN.
                boolean ids = keys.contains("accountNumber") || keys.contains("uan");
                add(k + "_derived", lab + "details", V, Type.TEXT, ids, sql, Live.NONE, v);
            }
        }

        // Credit bureau
        String C = G_BUREAU;
        f("bureau_score", "Bureau score", C, Type.NUMBER, "p.bureau_score", "p");
        f("bureau_source", "Bureau source", C, Type.TEXT, "p.bureau_source", "p");
        f("risk_category", "Risk category", C, Type.TEXT, "p.risk_category", "p");
        f("credit_star_rating", "Credit star rating", C, Type.NUMBER, "p.credit_star_rating", "p");
        f("credit_recommendation", "Credit recommendation", C, Type.TEXT, "p.credit_recommendation", "p");
        f("credit_brief_summary", "Credit brief summary", C, Type.TEXT, "p.credit_brief_summary", "p");
        f("credit_brief_generated_at", "Credit brief generated at", C, Type.DATETIME, ts("p.credit_brief_generated_at"), "p");
        f("bureau_phones", "Phone numbers on bureau (summary)", C, Type.TEXT,
                hist("concat_ws(' ', x.mobile, x.source, " + d("x.reported_date") + ", case when x.identity_mismatch then 'identity mismatch' end)",
                        "bureau_phone x where x.customer_id = a.customer_id", "x.id", 20));
        f("bank_statement_status", "Bank statement analysis status", C, Type.TEXT,
                "(select x.status from bank_statement_analysis x where x.application_id = a.id order by x.id desc limit 1)");
        f("bank_statement_report_status", "Bank statement report status", C, Type.TEXT,
                "(select x.report_status from bank_statement_analysis x where x.application_id = a.id order by x.id desc limit 1)");
        f("bank_statement_score", "Bank statement score", C, Type.TEXT,
                "(select x.bank_score from bank_statement_analysis x where x.application_id = a.id order by x.id desc limit 1)");

        // References
        String F = G_REFERENCES;
        for (int slot = 1; slot <= 2; slot++) {
            String w = "(select %s from application_reference x where x.application_id = a.id and x.slot = " + slot + ")";
            f("reference" + slot + "_name", "Reference " + slot + " name", F, Type.TEXT, w.formatted("x.full_name"));
            f("reference" + slot + "_mobile", "Reference " + slot + " mobile", F, Type.TEXTNUM, w.formatted("x.mobile"));
            f("reference" + slot + "_relation", "Reference " + slot + " relation", F, Type.TEXT, w.formatted("x.relation"));
        }

        // Mandates and debits
        String M = G_MANDATES;
        f("mandate_status", "Mandate status", M, Type.TEXT, "m.status", "m");
        f("mandate_umrn", "Mandate UMRN", M, Type.TEXT, "m.umrn", "m");
        f("mandate_bank_name", "Mandate bank", M, Type.TEXT, "m.bank_name", "m");
        f("mandate_account_last4", "Mandate account (last 4)", M, Type.TEXTNUM, "m.account_last4", "m");
        f("mandate_ifsc", "Mandate IFSC", M, Type.TEXT, "m.ifsc", "m");
        f("mandate_maximum_amount", "Mandate maximum amount", M, Type.MONEY, "m.maximum_amount_paise", "m");
        f("mandate_registered_at", "Mandate registered at", M, Type.DATETIME, ts("m.registered_at"), "m");
        f("mandate_cancelled_at", "Mandate cancelled at", M, Type.DATETIME, ts("m.cancelled_at"), "m");
        f("mandate_cancel_reason", "Mandate cancel reason", M, Type.TEXT, "m.cancel_reason", "m");
        f("mandate_reject_reason", "Mandate reject reason", M, Type.TEXT, "m.reject_reason", "m");
        f("debit_attempts", "Debit attempts", M, Type.NUMBER,
                "(select count(*) from mandate_debit x where x.loan_id = l.id)", "l");
        f("debit_last_status", "Last debit status", M, Type.TEXT, "md.status", "md");
        f("debit_last_failure", "Last debit failure", M, Type.TEXT, "concat_ws(' ', md.failure_code, md.failure_reason)", "md");
        f("debit_last_utr", "Last debit UTR", M, Type.TEXT, "md.utr", "md");
        f("debit_history", "Debit history (summary)", M, Type.TEXT,
                hist("concat_ws(' ', " + d("x.settlement_date") + ", " + rs("x.amount_paise") + ", x.status, x.failure_reason, x.utr)",
                        "mandate_debit x where x.loan_id = l.id", "x.id", 20), "l");

        // Collections
        String K = G_COLLECTIONS;
        f("case_id", "Collection case ID", K, Type.TEXT, "cc.id::text", "cc");
        f("case_opened", "Case opened", K, Type.DATETIME, ts("cc.created_at"), "cc");
        f("case_officer", "Collection officer", K, Type.TEXT, staff("cc.assigned_officer_id"), "cc");
        f("case_dpd_bucket", "Current DPD bucket (computed)", K, Type.TEXT,
                "case when cc.id is not null then " + bucket("l") + " end", "cc", "l");
        f("interaction_history", "Interactions (summary)", K, Type.TEXT,
                hist("concat_ws(' ', " + ts("x.logged_at") + ", x.type, x.outcome, case when x.promise_to_pay_date is not null then 'promise ' || "
                                + d("x.promise_to_pay_date") + " end)",
                        "interaction_log x where x.collection_case_id = cc.id", "x.logged_at", 30), "cc");
        f("last_interaction", "Last interaction", K, Type.TEXT,
                "(select concat_ws(' ', " + ts("x.logged_at") + ", x.type, x.outcome) from interaction_log x"
                        + " where x.collection_case_id = cc.id order by x.logged_at desc limit 1)", "cc");
        f("promise_to_pay", "Promise to pay date", K, Type.DATE,
                "(select " + d("x.promise_to_pay_date") + " from interaction_log x where x.collection_case_id = cc.id"
                        + " and x.promise_to_pay_date is not null order by x.logged_at desc limit 1)", "cc");
        f("settlement_status", "Settlement status", K, Type.TEXT,
                "(select x.status from settlement x where x.collection_case_id = cc.id order by x.created_at desc limit 1)", "cc");
        f("settlement_amount", "Settlement amount", K, Type.MONEY,
                "(select x.settlement_amount from settlement x where x.collection_case_id = cc.id order by x.created_at desc limit 1)", "cc");
        f("repayment_plan_revised_date", "Revised repayment plan date", K, Type.DATE,
                "(select " + d("x.revised_due_date") + " from repayment_plan x where x.collection_case_id = cc.id"
                        + " order by x.created_at desc limit 1)", "cc");
        f("skip_trace_status", "Skip trace status", K, Type.TEXT,
                "(select x.status from skip_trace x where x.customer_id = a.customer_id order by x.id desc limit 1)");

        // Calls, notes and owners
        String N = G_CALLS;
        f("owner", "Owner", N, Type.TEXT,
                staff("(select o.owner_staff_id from customer_owner o where o.customer_id = a.customer_id)"));
        f("owner_assigned_at", "Owner assigned at", N, Type.DATETIME,
                "(select " + ts("o.assigned_at") + " from customer_owner o where o.customer_id = a.customer_id)");
        f("call_history", "Call log (summary)", N, Type.TEXT,
                hist("concat_ws(' ', " + ts("x.created_at") + ", x.call_type, x.outcome, case when x.callback_on is not null then 'callback '"
                                + " || " + d("x.callback_on") + " end, left(x.notes, 300))",
                        "customer_call_log x where x.customer_id = a.customer_id", "x.id", 20));
        f("last_call_outcome", "Last call outcome", N, Type.TEXT,
                "(select x.outcome from customer_call_log x where x.customer_id = a.customer_id order by x.id desc limit 1)");
        f("last_call_at", "Last call at", N, Type.DATETIME,
                "(select " + ts("x.created_at") + " from customer_call_log x where x.customer_id = a.customer_id order by x.id desc limit 1)");
        f("callback_on", "Callback on", N, Type.DATE,
                "(select " + d("x.callback_on") + " from customer_call_log x where x.customer_id = a.customer_id"
                        + " and x.callback_on is not null order by x.id desc limit 1)");
        f("remarks_history", "Remarks (summary)", N, Type.TEXT,
                hist("concat_ws(' ', " + d("x.created_at at time zone 'Asia/Kolkata'") + ", left(x.body, 300))",
                        "customer_remark x where x.customer_id = a.customer_id", "x.id", 20));
        f("credit_limit_override", "Credit limit override", N, Type.MONEY,
                "(select x.limit_paise from customer_limit_override x where x.customer_id = a.customer_id)");
        f("mandate_ceiling_override", "Mandate ceiling override", N, Type.MONEY,
                "(select x.ceiling_paise from customer_mandate_ceiling_override x where x.customer_id = a.customer_id)");
        f("pref_email_opt_in", "Email opt-in", N, Type.BOOL,
                "(select x.email_opt_in from borrower_preferences x where x.customer_id = a.customer_id)");
        f("pref_sms_opt_in", "SMS opt-in", N, Type.BOOL,
                "(select x.sms_opt_in from borrower_preferences x where x.customer_id = a.customer_id)");
        f("pref_offers_opt_in", "Offers opt-in", N, Type.BOOL,
                "(select x.offers_opt_in from borrower_preferences x where x.customer_id = a.customer_id)");
        f("rejection_reason", "Rejection reason", N, Type.TEXT, "rj.reason_code", "rj");
        f("rejection_detail", "Rejection detail", N, Type.TEXT, "rj.reason_detail", "rj");
        f("rejection_blocked_until", "Rejection blocked until", N, Type.DATETIME, ts("rj.blocked_until"), "rj");
        f("rejection_auto", "Rejected automatically", N, Type.BOOL, "rj.auto", "rj");
        f("block_status", "Block status", N, Type.TEXT,
                "case when exists (select 1 from application_rejection r where r.mobile = p.mobile and r.blocked_until > now())"
                        + " then 'Blocked' else 'Not blocked' end", "p");
        f("blocklisted", "On the blocklist", N, Type.BOOL,
                "exists (select 1 from blocklist_entry b where b.active and b.type in ('PAN', 'PHONE')"
                        + " and b.value in (p.pan, p.mobile))", "p");

        // Documents and consent
        String D = G_DOCUMENTS;
        f("document_count", "Documents uploaded", D, Type.NUMBER,
                "(select count(*) from application_document x where x.application_id = a.id)");
        f("documents", "Documents (type, file name, date)", D, Type.TEXT,
                hist("concat_ws(' ', x.doc_type, x.file_name, " + d("x.created_at at time zone 'Asia/Kolkata'") + ")",
                        "application_document x where x.application_id = a.id", "x.id", 30));
        f("loan_documents", "Loan documents (types)", D, Type.TEXT,
                "(select string_agg(distinct x.type, ', ') from loan_document x where x.loan_id = l.id)", "l");

        // Referral and lead source
        String Y = G_REFERRAL;
        f("referral_code", "Referral code", Y, Type.TEXT,
                "(select x.code from referral_code x where x.customer_id = a.customer_id order by x.id desc limit 1)");
        f("referred_by_customer_id", "Referred by (customer ID)", Y, Type.NUMBER,
                "(select x.referrer_customer_id from referral x where x.referred_customer_id = a.customer_id order by x.id limit 1)");
        f("referred_by_name", "Referred by (name)", Y, Type.TEXT,
                "(select p2.full_name from customer_profile p2 join loan_application a2 on a2.id = p2.application_id"
                        + " where a2.customer_id = (select x.referrer_customer_id from referral x"
                        + " where x.referred_customer_id = a.customer_id order by x.id limit 1) order by a2.id desc limit 1)");
        f("referral_status", "Referral status", Y, Type.TEXT,
                "(select x.status from referral x where x.referred_customer_id = a.customer_id order by x.id limit 1)");
        f("referrals_made", "Customers referred", Y, Type.NUMBER,
                "(select count(*) from referral x where x.referrer_customer_id = a.customer_id)");
        f("referral_payouts", "Referral payouts (summary)", Y, Type.TEXT,
                hist("concat_ws(' ', x.beneficiary_role, " + rs("x.amount_paise") + ", x.status, x.txn_ref)",
                        "referral_payout x where x.beneficiary_customer_id = a.customer_id", "x.id", 20));
        f("lead_source", "Lead source", Y, Type.TEXT,
                "(select x.source from lead x where x.mobile = p.mobile order by x.id desc limit 1)", "p");
        f("lead_source_detail", "Lead source detail", Y, Type.TEXT,
                "(select x.source_detail from lead x where x.mobile = p.mobile order by x.id desc limit 1)", "p");
    }

    private static String vAlias(String checkType) {
        return "v_" + checkType.toLowerCase();
    }

    // ── access ───────────────────────────────────────────────────────────────────────────────

    public static List<Field> all() {
        return Collections.unmodifiableList(FIELDS);
    }

    public static Field byKey(String key) {
        return BY_KEY.get(key);
    }

    /** Names of every join, for the tests. */
    static Set<String> joinNames() {
        return JOINS.keySet();
    }

    /** The JOIN clauses a set of selected fields needs (their dependencies included), in definition order. */
    static String joinSql(Set<String> needed, Grain grain) {
        Set<String> all = new LinkedHashSet<>(needed);
        for (String n : needed) {
            all.addAll(JOINS.get(n).deps());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JoinDef> e : JOINS.entrySet()) {
            if (all.contains(e.getKey())) {
                sb.append(' ').append(grain == Grain.CUSTOMER ? e.getValue().customerSql() : e.getValue().applicationSql());
            }
        }
        return sb.toString();
    }
}
