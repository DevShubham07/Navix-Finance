package com.navix.loan.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The admin-sendable message catalogue (V95), constants only. {@code provider} is the stored
 * WhatsApp template name (Meta owns the wording); {@code params} is its ordered parameter list and
 * must never be reordered without an approved template of that name.
 *
 * <p>Parameter names: auto-filled {@code name, amount, loanId, daysOverdue, dailyCharge, dueDate,
 * dueDatePlus5, salaryDate, repayBy, percent}, or {@code in:<input>} for a value the admin types.
 *
 * <p>Besides the per-percentage discount SMS, only {@code payment_overdue} and {@code payment_due_soon} have SMS: their body and DLT key are the
 * registered ones from {@code NotificationTemplates} / {@code navix.sms.dlt-template-ids} (a navix-app
 * test asserts they stay equal; this module cannot see navix-notification).
 */
public final class MessageCatalogue {

    private MessageCatalogue() {
    }

    public static final String SMS = "SMS";
    public static final String WHATSAPP = "WHATSAPP";
    public static final String SERVICE = "SERVICE";
    public static final String MARKETING = "MARKETING";

    /** Which loans a template makes sense for (every template also needs a live loan). */
    public enum Rule { LIVE, OVERDUE, WINDOW_1_5 }

    public record Input(String name, String label, String type) {
    }

    public record Template(String key, String label, String category, String provider, String reviewProvider,
                           List<String> channels, List<String> params, String smsBody, String smsDltKey,
                           List<Input> inputs, boolean singleOnly, String confirmText, Rule rule,
                           boolean discount) {

        public boolean hasSms() {
            return channels.contains(SMS);
        }

        public String providerFor(int daysOverdue) {
            return discount && reviewProvider != null
                    && daysOverdue > ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS
                    ? reviewProvider : provider;
        }
    }

    private static final String REPORTED_CONFIRM =
            "MLB has actually reported this loan to the credit bureaus, confirmed in writing.";
    private static final String STRICT_CONFIRM =
            "MLB has confirmed in writing that it reports overdue loans to the credit bureaus.";

    private static final List<String> OVERDUE_PARAMS = List.of("name", "amount", "loanId", "daysOverdue");
    private static final List<String> BASIC_PARAMS = List.of("name", "amount", "loanId");
    private static final List<String> WA = List.of(WHATSAPP);

    private static final Input DATE_IN = new Input("date", "Date", "DATE");

    public static final List<Template> ALL = List.of(
            t("overdue_soft_d7", "Overdue reminder (soft), day 7", "db_overdue_soft_d7_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false, null),
            t("overdue_soft_d15", "Overdue reminder (soft), day 15", "db_overdue_soft_d15_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false, null),
            t("overdue_soft_d30", "Overdue reminder (soft), day 30", "db_overdue_soft_d30_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false, null),
            t("reported_soft", "Reported to bureaus (soft)", "db_reported_soft_v1_164",
                    BASIC_PARAMS, Rule.LIVE, List.of(), false, REPORTED_CONFIRM),
            t("overdue_strict_d7", "Overdue notice (strict), day 7", "db_overdue_strict_d7_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false, STRICT_CONFIRM),
            t("overdue_strict_d15", "Overdue notice (strict), day 15", "db_overdue_strict_d15_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false, STRICT_CONFIRM),
            t("overdue_strict_d30", "Overdue notice (strict), day 30", "db_overdue_strict_d30_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false,
                    "Earlier reminders really were sent, and MLB has confirmed reporting in writing."),
            t("reported_strict", "Reported to bureaus (strict)", "db_reported_strict_v1_164",
                    BASIC_PARAMS, Rule.LIVE, List.of(), false, REPORTED_CONFIRM),
            t("cost_clock", "Daily cost of staying overdue", "db_cost_clock_v1_164",
                    List.of("name", "amount", "loanId", "dailyCharge"), Rule.OVERDUE, List.of(), false, null),
            t("eligibility_window", "Eligibility window closing", "db_eligibility_window_v1_164",
                    List.of("name", "amount", "loanId", "dueDatePlus5"), Rule.WINDOW_1_5, List.of(), false, null),
            t("salary_day", "Salary day reminder", "db_salary_day_v1_164",
                    List.of("name", "salaryDate", "amount", "loanId"), Rule.LIVE, List.of(), false, null),
            t("ptp_confirmation", "Promise to pay: confirmation", "db_ptp_confirmation_v1_164",
                    List.of("name", "in:amount", "loanId", "in:date"), Rule.LIVE,
                    List.of(amountIn("Promised amount (Rs)"), DATE_IN), true, null),
            t("ptp_missed", "Promise to pay: missed", "db_ptp_missed_v1_164",
                    List.of("name", "in:amount", "in:date", "loanId"), Rule.LIVE,
                    List.of(amountIn("Promised amount (Rs)"), DATE_IN), true, null),
            t("part_payment", "Part payment received", "db_part_payment_v1_164",
                    BASIC_PARAMS, Rule.LIVE, List.of(), false, null),
            t("officer_assigned", "Collection officer assigned", "db_officer_assigned_v1_164",
                    List.of("name", "loanId", "in:officer", "in:phone"), Rule.LIVE,
                    List.of(new Input("officer", "Officer name", "TEXT"),
                            new Input("phone", "Officer phone", "TEXT")), false, null),
            t("no_response", "No response to calls", "db_no_response_v1_164",
                    BASIC_PARAMS, Rule.LIVE, List.of(), false, null),
            t("settlement_offer", "Settlement offer", "db_settlement_offer_v1_164",
                    List.of("name", "in:amount", "loanId", "in:date"), Rule.LIVE,
                    List.of(amountIn("Settlement amount (Rs)"), DATE_IN), true,
                    "The Collection Head approved this settlement."),
            t("senior_review", "Referred to senior collections", "db_senior_review_v1_164",
                    OVERDUE_PARAMS, Rule.OVERDUE, List.of(), false,
                    "This account really has been referred to the senior collections team."),
            t("notice_issued", "Formal notice issued", "db_notice_issued_v1_164",
                    List.of("name", "amount", "loanId", "in:date"), Rule.LIVE, List.of(DATE_IN), true,
                    "A formal notice really was issued on this date."),
            t("visit_notice", "Visit notice", "db_visit_notice_v1_164",
                    List.of("name", "loanId", "in:representative", "in:date"), Rule.LIVE,
                    List.of(new Input("representative", "Representative name", "TEXT"), DATE_IN), true,
                    "This visit is really scheduled."),
            new Template("repay_discount", "Repay-now fee discount", MARKETING, "db_repay_discount_v1_164",
                    "db_repay_discount_review_v1_164", List.of(SMS, WHATSAPP), List.of("name", "loanId", "repayBy", "percent"),
                    null, null,
                    List.of(new Input("percent", "Discount percent (1 to 7)", "PERCENT"),
                            new Input("repayBy", "Repay by", "DATE")),
                    false, null, Rule.LIVE, true),
            new Template("payment_overdue", "Payment overdue", SERVICE, "db_payment_overdue", null,
                    List.of(SMS, WHATSAPP), OVERDUE_PARAMS,
                    "Dear {name}, repayment of {amount} on your DhanBoost loan {loanId} is overdue by "
                            + "{daysOverdue} day(s). Pay at https://dhanboost.com/login. - DhanBoost",
                    "PAYMENT_OVERDUE", List.of(), false, null, Rule.OVERDUE, false),
            new Template("payment_due_soon", "Payment due soon", SERVICE, "db_payment_due_soon", null,
                    List.of(SMS, WHATSAPP), List.of("name", "amount", "loanId", "dueDate"),
                    "Dear {name}, repayment of {amount} on your DhanBoost loan {loanId} is due on {dueDate}. "
                            + "Pay at https://dhanboost.com/login. - DhanBoost",
                    "PAYMENT_DUE_SOON", List.of(), false, null, Rule.LIVE, false));

    public static Template find(String key) {
        return ALL.stream().filter(t -> t.key().equals(key)).findFirst().orElse(null);
    }

    private static Input amountIn(String label) {
        return new Input("amount", label, "TEXT");
    }

    private static Template t(String key, String label, String provider, List<String> params, Rule rule,
                              List<Input> inputs, boolean singleOnly, String confirm) {
        return new Template(key, label, SERVICE, provider, null, WA, params, null, null, inputs, singleOnly,
                confirm, rule, false);
    }

    private static final Pattern TOKEN = Pattern.compile("\\{(\\w+)\\}");

    /** Substitute {@code {name}} tokens positionally from {@code values} (param name to value). */
    public static String renderSms(Template template, Map<String, String> values) {
        Matcher m = TOKEN.matcher(template.smsBody());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "—")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The DLT key of a discount SMS. Each is registered with the percentage as fixed text, because DLT
     * refused it as a variable, and it is stated off the processing fee: the fee is 10% of the loan,
     * so 5% of the loan is 50% of the fee.
     */
    public static String discountSmsKey(int loanPercent, boolean review) {
        return "REPAY_FEE" + (loanPercent * 10) + (review ? "_REVIEW" : "");
    }

    /** Must match the registered DLT text character for character. */
    public static String discountSms(int loanPercent, boolean review, String name, String loanId, String repayBy) {
        return "Dear " + name + ", repay loan " + loanId + " by " + repayBy + " for " + (loanPercent * 10)
                + "% off processing fee on next " + (review ? "loan after review" : "approved loan")
                + ". Pay at https://dhanboost.com/login. - DhanBoost";
    }

    /** Ordered name-to-value map for a resolved param list (for SMS rendering). */
    public static Map<String, String> byName(Template template, List<String> values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < template.params().size() && i < values.size(); i++) {
            map.put(template.params().get(i), values.get(i));
        }
        return map;
    }
}
