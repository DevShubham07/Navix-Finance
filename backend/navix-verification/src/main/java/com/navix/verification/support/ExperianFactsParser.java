package com.navix.verification.support;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.lng;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.verification.BureauDelinquency;
import com.navix.common.verification.BureauDetail;
import com.navix.common.verification.BureauEnquiry;
import com.navix.common.verification.BureauEnquiryVelocity;
import com.navix.common.verification.BureauCodes;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauTradeline;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a standard Experian credit-report body into the provider-neutral {@link BureauReportFacts}
 * (Categories A/B/C, plus the itemised {@link BureauDetail}) used by the loan module's credit brief +
 * 1–5★ rating.
 *
 * <p>The Experian JSON is the SAME shape whichever aggregator returns it — Signzy exposes it at
 * {@code data.jsonExperianReport}, Digitap at {@code result.result_json.INProfileResponse} — so both
 * bureau clients unwrap their envelope to the report node and hand it here. The customer identity
 * (name/PAN/mobile) is passed in from the caller's request rather than harvested from the report,
 * because the credit brief overrides identity from the KYC profile anyway
 * ({@code CreditBriefService.displayFacts}).
 *
 * <p>Returns {@code null} for a thin-file response (no CAIS account/balance summary) so callers can
 * degrade gracefully. All bureau values are parsed defensively (blank → {@code null}); monetary
 * amounts are in rupees (the bureau's native unit).
 *
 * <p><b>This parser was built against 6,417 real tradelines across 36 production Experian responses,
 * NOT the bundled {@code samplepan.json} fixture.</b> The fixture is Signzy/Fintrix-shaped, has masked
 * lender names, and carries an {@code Account_Review_Data} section that appears in zero production
 * responses — do not "complete" this parser by matching the fixture more closely; match the field
 * presence rates documented on each new record type in {@code navix-common/.../verification/}.
 */
public final class ExperianFactsParser {

    /**
     * {@code Days_Past_Due} sentinel value the bureau uses for "not applicable / not reported" — it is
     * NOT a day count. Observed 981 times across 6,417 production tradelines. A naive {@code max()}
     * over the raw DPD strings would print "worst DPD: 900 days" on a real customer's credit brief;
     * every DPD aggregation in this file MUST exclude this value (and blank strings, seen 2,833 times)
     * before taking a max/comparison.
     */
    /**
     * Experian reports long-defaulted accounts with a conventional {@code 900} in
     * {@code Days_Past_Due} — 981 of the 30,820 month-entries across production carry exactly this
     * value, a pronounced spike against 2,467 spread over 100–899.
     *
     * <p><b>It is a real reading, not a placeholder, and must never be discarded.</b> An earlier
     * revision of this parser skipped it as a sentinel; that silently threw away the single most
     * severe delinquency signal in the file and reported the cleanest remaining month instead, which
     * on a credit report understates risk — the one direction that must not fail. The value is also
     * not a hard ceiling (42 entries exceed it, up to 999), so it cannot be treated as a cap either.
     * It is kept as a number and rendered as "900+ days" at the display boundary.
     */
    static final int DPD_LONG_DEFAULT = 900;

    /**
     * Defensive cap on how many tradelines a single parsed report carries. The largest real production
     * file observed had 543 tradelines, so this leaves real headroom rather than sitting exactly on
     * the observed maximum — a cap set to today's largest file truncates the next borrower who is
     * merely slightly larger. It exists purely so a pathological/malformed response cannot blow up
     * memory or the {@code customer_profile.credit_brief_facts} jsonb column. The delinquency
     * aggregates are computed BEFORE this cap applies, and the true count is preserved in
     * {@link BureauDetail#tradelineCount()}, so a capped brief says "showing 1000 of N" rather than
     * silently under-reporting.
     */
    private static final int MAX_TRADELINES = 1000;

    private ExperianFactsParser() {
    }

    /**
     * @param report the Experian report node ({@code jsonExperianReport} / {@code INProfileResponse}).
     * @param score  the numeric bureau score already extracted by the client.
     * @param name   caller-known customer name (Category A).
     * @param pan    caller-known PAN (Category A).
     * @param mobile caller-known mobile (Category A).
     * @return categorized facts, or {@code null} on a thin-file report.
     */
    public static BureauReportFacts parse(JsonNode report, Integer score,
                                          String name, String pan, String mobile) {
        if (report == null || report.isMissingNode() || report.isNull()) {
            return null;
        }
        JsonNode appDetails = report.path("Current_Application").path("Current_Application_Details");
        JsonNode customer = appDetails.path("Current_Applicant_Details");
        JsonNode addressNode = appDetails.path("Current_Applicant_Address_Details");
        // Experian/Digitap have returned both shapes in production: an address object and a
        // one-or-more-address array. Read the first array item while preserving object responses.
        JsonNode addr = addressNode.isArray() ? addressNode.path(0) : addressNode;
        JsonNode caisAccount = report.path("CAIS_Account");
        JsonNode summary = caisAccount.path("CAIS_Summary");
        JsonNode creditAcct = summary.path("Credit_Account");
        JsonNode bal = summary.path("Total_Outstanding_Balance");
        // Thin-file: a score may be present but there is no account/balance detail to brief on.
        if (creditAcct.isMissingNode() && bal.isMissingNode()) {
            return null;
        }
        return new BureauReportFacts(
                name,
                pan,
                mobile,
                formatDate(text(customer.path("Date_Of_Birth_Applicant"))),
                text(addr.path("City")),
                text(addr.path("PINCode")),
                score,
                integer(creditAcct.path("CreditAccountTotal")),
                integer(creditAcct.path("CreditAccountActive")),
                integer(creditAcct.path("CreditAccountClosed")),
                integer(creditAcct.path("CreditAccountDefault")),
                lng(bal.path("Outstanding_Balance_All")),
                lng(bal.path("Outstanding_Balance_Secured")),
                lng(bal.path("Outstanding_Balance_UnSecured")),
                integer(report.path("TotalCAPS_Summary").path("TotalCAPSLast30Days")),
                text(report.path("CreditProfileHeader").path("ReportNumber")),
                parseDetail(report, caisAccount));
    }

    /** {@code "19850710"} → {@code "1985-07-10"}; anything not 8 digits is returned unchanged. */
    private static String formatDate(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            return t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);
        }
        return t.isBlank() ? null : t;
    }

    // ------------------------------------------------------------------------------------------
    // Detail: tradelines, enquiries, delinquency aggregates, enquiry velocity.
    // ------------------------------------------------------------------------------------------

    private static BureauDetail parseDetail(JsonNode report, JsonNode caisAccount) {
        List<JsonNode> tradelineNodes = asList(caisAccount.path("CAIS_Account_DETAILS"));
        int trueCount = tradelineNodes.size();
        // EVERY tradeline is parsed, because the delinquency aggregates below are counted over all of
        // them. Only the list we CARRY is capped: truncating before counting would under-report
        // delinquency on exactly the largest, most exposed files — an undercount on a credit report,
        // which is the one direction that must not fail. Parsing is cheap (a flat record per row);
        // the cap exists to bound what lands in the credit_brief_facts jsonb column and on screen.
        List<BureauTradeline> all = new ArrayList<>(trueCount);
        for (JsonNode node : tradelineNodes) {
            all.add(parseTradeline(node));
        }
        List<BureauTradeline> tradelines =
                all.size() <= MAX_TRADELINES ? all : List.copyOf(all.subList(0, MAX_TRADELINES));

        List<JsonNode> enquiryNodes = asList(report.path("CAPS").path("CAPS_Application_Details"));
        List<BureauEnquiry> enquiries = new ArrayList<>();
        for (JsonNode node : enquiryNodes) {
            enquiries.add(parseEnquiry(node));
        }

        BureauDelinquency delinquency = computeDelinquency(tradelineNodes, all);
        BureauEnquiryVelocity velocity = parseVelocity(report.path("TotalCAPS_Summary"));

        return new BureauDetail(tradelines, trueCount, enquiries, delinquency, velocity);
    }

    private static BureauTradeline parseTradeline(JsonNode node) {
        return new BureauTradeline(
                text(node.path("Subscriber_Name")),
                text(node.path("Account_Number")),
                text(node.path("Account_Type")),
                text(node.path("Portfolio_Type")),
                text(node.path("Account_Status")),
                formatDate(text(node.path("Open_Date"))),
                formatDate(text(node.path("Date_Closed"))),
                // Current_Balance is legitimately negative for some real tradelines (23/6417 observed)
                // — the sign is meaningful (a credit balance), so it is NOT clamped to zero here.
                lng(node.path("Current_Balance")),
                lng(node.path("Amount_Past_Due")),
                // Credit_Limit_Amount is populated in only ~2% of real tradelines (125/6417); carried
                // through as a raw display value only — see BureauTradeline's javadoc: never use it as
                // the denominator of a utilisation ratio.
                lng(node.path("Credit_Limit_Amount")),
                text(node.path("Payment_History_Profile")),
                text(node.path("Written_off_Settled_Status")),
                lng(node.path("Settlement_Amount")),
                worstDpd(node.path("CAIS_Account_History"), Integer.MAX_VALUE));
    }

    private static BureauEnquiry parseEnquiry(JsonNode node) {
        return new BureauEnquiry(
                formatDate(text(node.path("Date_of_Request"))),
                text(node.path("Subscriber_Name")),
                text(node.path("Enquiry_Reason")),
                lng(node.path("Amount_Financed")),
                integer(node.path("Duration_Of_Agreement")));
    }

    private static BureauEnquiryVelocity parseVelocity(JsonNode totalCapsSummary) {
        if (totalCapsSummary == null || totalCapsSummary.isMissingNode() || totalCapsSummary.isNull()) {
            return new BureauEnquiryVelocity(null, null, null, null);
        }
        return new BureauEnquiryVelocity(
                integer(totalCapsSummary.path("TotalCAPSLast7Days")),
                integer(totalCapsSummary.path("TotalCAPSLast30Days")),
                integer(totalCapsSummary.path("TotalCAPSLast90Days")),
                integer(totalCapsSummary.path("TotalCAPSLast180Days")));
    }

    /**
     * Worst (highest) non-blank {@code Days_Past_Due} figure across the first
     * {@code monthWindow} entries of one tradeline's {@code CAIS_Account_History} (entries are reported
     * newest-first, the standard Experian CAIS ordering — so the first {@code monthWindow} entries are
     * the trailing {@code monthWindow} reporting months). Pass {@link Integer#MAX_VALUE} for "all-time".
     *
     * <p>Returns {@code null} — not {@code 0} — when every entry in the window was blank or
     * unparseable; a tradeline with genuinely no usable DPD data must not be reported as "0 DPD"
     * (a clean record), which is a materially different claim. Values of {@link #DPD_LONG_DEFAULT}
     * and above are genuine long-default readings and are counted, not skipped.
     */
    private static Integer worstDpd(JsonNode history, int monthWindow) {
        if (history == null || history.isMissingNode() || history.isNull()) {
            return null;
        }
        List<JsonNode> entries = asList(history);
        Integer worst = null;
        int examined = 0;
        for (JsonNode entry : entries) {
            if (examined >= monthWindow) {
                break;
            }
            examined++;
            String raw = text(entry.path("Days_Past_Due"));
            if (raw == null) {
                continue;
            }
            if (raw.trim().isEmpty()) {
                continue;
            }
            Integer dpd = integer(entry.path("Days_Past_Due"));
            if (dpd == null) {
                continue;
            }
            if (worst == null || dpd > worst) {
                worst = dpd;
            }
        }
        return worst;
    }

    /**
     * Aggregates over <b>every</b> tradeline in the report, not the capped display list — see the
     * comment in {@code parseDetail}. Each figure stays {@code null} until something is actually
     * counted, so "no data" never renders as a reassuring zero.
     */
    private static BureauDelinquency computeDelinquency(
            List<JsonNode> tradelineNodes, List<BureauTradeline> tradelines) {
        Integer worst12 = null;
        Integer worst24 = null;
        Integer worst36 = null;
        // Counting counters start at 0 as soon as there is at least one tradeline to count, and stay
        // null only when there was nothing to examine. The distinction is the whole point: "0 accounts
        // ever 30+ DPD" on a file whose 11 tradelines we actually read is a finding — the file is
        // clean — whereas "—" means we could not tell. Collapsing the two in either direction misleads
        // the reviewer: always-null hides a clean record behind a dash, always-zero invents a clean
        // record we never verified.
        // Zero vs unknown is decided PER METRIC, from whether any tradeline actually carried the
        // field that metric counts — not merely from "there were tradelines". A tradeline with no
        // Amount_Past_Due tells us nothing about arrears, so counting it as "0 past due" would
        // manufacture a clean record; but Written_off_Settled_Status is absent on 5,910 of 6,417
        // production tradelines precisely BECAUSE they are not written off, so there absence is the
        // finding. Getting this per-field is the difference between "this file is clean" and "we
        // could not tell", which is the single most important distinction on a credit report.
        boolean anyDpdKnown = tradelines.stream().anyMatch(t -> t.worstDpdMonths() != null);
        boolean anyPastDueKnown = tradelines.stream().anyMatch(t -> t.amountPastDueRupees() != null);
        Integer ever30Plus = anyDpdKnown ? 0 : null;
        Integer currentlyPastDue = anyPastDueKnown ? 0 : null;
        Integer writtenOffOrSettled = tradelines.isEmpty() ? null : 0;
        String oldestOpen = null;

        for (JsonNode node : tradelineNodes) {
            JsonNode history = node.path("CAIS_Account_History");
            worst12 = max(worst12, worstDpd(history, 12));
            worst24 = max(worst24, worstDpd(history, 24));
            worst36 = max(worst36, worstDpd(history, 36));
        }
        for (BureauTradeline t : tradelines) {
            if (t.worstDpdMonths() != null && t.worstDpdMonths() >= 30) {
                ever30Plus = ever30Plus + 1;
            }
            if (t.amountPastDueRupees() != null && t.amountPastDueRupees() > 0) {
                currentlyPastDue = currentlyPastDue + 1;
            }
            if (t.writtenOffSettledStatus() != null && !t.writtenOffSettledStatus().isBlank()) {
                writtenOffOrSettled = writtenOffOrSettled + 1;
            }
            // Open_Date is normalised to YYYY-MM-DD, so plain string comparison is chronological.
            String opened = t.openedOn();
            if (isIsoDate(opened) && (oldestOpen == null || opened.compareTo(oldestOpen) < 0)) {
                oldestOpen = opened;
            }
        }
        return new BureauDelinquency(
                worst12, worst24, worst36, ever30Plus, currentlyPastDue, writtenOffOrSettled, oldestOpen);
    }

    private static boolean isIsoDate(String s) {
        return s != null && s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-';
    }

    private static Integer max(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    /**
     * Tolerates all four shapes seen in production for a bureau sub-section: missing/null (empty list),
     * a JSON array (its elements), or a single JSON object (a one-element list) — the same tolerance
     * {@code parse()} already applies to {@code Current_Applicant_Address_Details}. Real Experian
     * responses use a bare object instead of a one-element array whenever exactly one tradeline/enquiry
     * exists, which a naive {@code node.isArray()}-only reader would silently drop.
     */
    private static List<JsonNode> asList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            node.forEach(out::add);
            return out;
        }
        if (node.isObject()) {
            return List.of(node);
        }
        return List.of();
    }
}
