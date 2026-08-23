package com.navix.verification.support;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.verification.BureauDelinquency;
import com.navix.common.verification.BureauDetail;
import com.navix.common.verification.BureauEnquiry;
import com.navix.common.verification.BureauEnquiryVelocity;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauScoreHistory;
import com.navix.common.verification.BureauTradeline;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a CRIF Highmark {@code /crif_combine} report body into the provider-neutral
 * {@link BureauReportFacts} — the SAME target shape {@link ExperianFactsParser} produces, so the
 * credit brief / rating / PDF / staff UI / stored jsonb all keep working whichever bureau answered.
 *
 * <p>{@code report} is the {@code canonical.data.credit_report} node of the Fintrix envelope. The
 * customer identity (name/PAN/mobile) and score are passed in by the caller, never harvested from the
 * report, mirroring {@link ExperianFactsParser}.
 *
 * <p><b>CRIF's own summary balance is unreliable and is deliberately never used.</b> A real production
 * response carried {@code PRIMARY-CURRENT-BALANCE = "0"} while its seven tradelines summed to ~₹8.19L —
 * so {@code total/secured/unsecuredBalanceRupees} are always summed from {@code RESPONSES.RESPONSE[].
 * LOAN-DETAILS.CURRENT-BAL}, split on {@code SECURITY-STATUS}, never read off the summary node. Do not
 * "simplify" this back to reading the summary field — it silently zeroes out real exposure.
 *
 * <p>Same discipline as {@link ExperianFactsParser}: monetary amounts are in rupees, {@code null} means
 * "could not determine" and is never conflated with a real {@code 0}, {@code creditLimitRupees} is
 * never a utilisation denominator, and every sub-section tolerates missing / null / bare-object shapes
 * (CRIF emits a bare object, not a one-element array, whenever exactly one entry exists — see
 * {@link #asList}). Returns {@code null} for a thin-file response so callers degrade gracefully, same
 * as {@link ExperianFactsParser}.
 */
public final class CrifHighmarkFactsParser {

    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private CrifHighmarkFactsParser() {
    }

    /**
     * @param report the {@code canonical.data.credit_report} node of the Fintrix {@code /crif_combine}
     *               response.
     * @param score  the numeric bureau score already extracted by the caller.
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
        JsonNode accountsSummary = report.path("ACCOUNTS-SUMMARY");
        JsonNode responses = report.path("RESPONSES");
        // Thin-file: neither an account summary nor any tradeline detail to brief on.
        if (accountsSummary.isMissingNode() && responses.isMissingNode()) {
            return null;
        }
        List<JsonNode> tradelineNodes = asList(responses.path("RESPONSE")).stream()
                .map(node -> node.path("LOAN-DETAILS"))
                .toList();

        JsonNode header = report.path("HEADER");
        JsonNode request = report.path("REQUEST");
        String[] cityPin = cityPin(report.path("PERSONAL-INFO-VARIATION").path("ADDRESS-VARIATIONS"));

        JsonNode primary = accountsSummary.path("PRIMARY-ACCOUNTS-SUMMARY");
        JsonNode secondary = accountsSummary.path("SECONDARY-ACCOUNTS-SUMMARY");

        BalanceTotals balances = sumBalances(tradelineNodes);
        Counts counts = countStatuses(tradelineNodes);
        LocalDate issuedOn = parseDate(text(header.path("DATE-OF-ISSUE")));

        return new BureauReportFacts(
                name,
                pan,
                mobile,
                formatDate(text(request.path("DOB"))),
                cityPin[0],
                cityPin[1],
                score,
                sumCounts(integer(primary.path("PRIMARY-NUMBER-OF-ACCOUNTS")),
                        integer(secondary.path("SECONDARY-NUMBER-OF-ACCOUNTS"))),
                sumCounts(integer(primary.path("PRIMARY-ACTIVE-NUMBER-OF-ACCOUNTS")),
                        integer(secondary.path("SECONDARY-ACTIVE-NUMBER-OF-ACCOUNTS"))),
                counts.closed(),
                counts.defaults(),
                balances.total(),
                balances.secured(),
                balances.unsecured(),
                countWithin(asList(report.path("INQUIRY-HISTORY").path("HISTORY")), issuedOn, 30),
                text(header.path("REPORT-ID")),
                parseDetail(report, tradelineNodes, issuedOn));
    }

    /** {@code "01-01-1990"} (CRIF's {@code dd-MM-yyyy}) &rarr; {@code "1990-01-01"} (ISO). */
    private static String formatDate(String raw) {
        LocalDate d = parseDate(raw);
        if (d != null) {
            return d.toString();
        }
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DDMMYYYY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer sumCounts(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    /**
     * City/PIN come from the {@code ADDRESS-VARIATIONS} entry with the newest {@code REPORTED-DATE};
     * the PIN is the trailing 6-digit token of its {@code VALUE}, and the city is the token immediately
     * before it (the state-code token, if any, trails the PIN). Neither field is asserted precisely by
     * the test suite — free-text address strings vary too much for an exact contract — this is a
     * best-effort display value, not authoritative.
     */
    private static String[] cityPin(JsonNode addressVariations) {
        List<JsonNode> variations = asList(addressVariations.path("VARIATION"));
        JsonNode newest = null;
        LocalDate newestDate = null;
        for (JsonNode v : variations) {
            LocalDate reported = parseDate(text(v.path("REPORTED-DATE")));
            if (reported != null && (newestDate == null || reported.isAfter(newestDate))) {
                newestDate = reported;
                newest = v;
            }
        }
        if (newest == null) {
            return new String[]{null, null};
        }
        String value = text(newest.path("VALUE"));
        if (value == null) {
            return new String[]{null, null};
        }
        String[] tokens = value.trim().split("\\s+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (tokens[i].matches("\\d{6}")) {
                String city = i > 0 ? tokens[i - 1] : null;
                return new String[]{city, tokens[i]};
            }
        }
        return new String[]{null, null};
    }

    // ------------------------------------------------------------------------------------------
    // Balances & status counts, summed/counted over RESPONSES (never the unreliable summary node).
    // ------------------------------------------------------------------------------------------

    private record BalanceTotals(Long total, Long secured, Long unsecured) {
    }

    private static BalanceTotals sumBalances(List<JsonNode> tradelineNodes) {
        Long total = null;
        Long secured = null;
        Long unsecured = null;
        for (JsonNode t : tradelineNodes) {
            Long bal = MoneyParser.indianRupees(text(t.path("CURRENT-BAL")));
            if (bal == null) {
                continue;
            }
            total = (total == null ? 0L : total) + bal;
            if ("Secured".equalsIgnoreCase(text(t.path("SECURITY-STATUS")))) {
                secured = (secured == null ? 0L : secured) + bal;
            } else {
                unsecured = (unsecured == null ? 0L : unsecured) + bal;
            }
        }
        return new BalanceTotals(total, secured, unsecured);
    }

    private record Counts(Integer closed, Integer defaults) {
    }

    private static Counts countStatuses(List<JsonNode> tradelineNodes) {
        if (tradelineNodes.isEmpty()) {
            return new Counts(null, null);
        }
        int closed = 0;
        int defaults = 0;
        for (JsonNode t : tradelineNodes) {
            String status = text(t.path("ACCOUNT-STATUS"));
            if ("Closed".equalsIgnoreCase(status)) {
                closed++;
            }
            Long writeOff = MoneyParser.indianRupees(text(t.path("WRITE-OFF-AMT")));
            String upperStatus = status == null ? "" : status.toUpperCase();
            boolean isDefault = (writeOff != null && writeOff > 0)
                    || upperStatus.contains("WRITE") || upperStatus.contains("DEFAULT")
                    || upperStatus.contains("SETTLED");
            if (isDefault) {
                defaults++;
            }
        }
        return new Counts(closed, defaults);
    }

    // ------------------------------------------------------------------------------------------
    // Detail: tradelines, enquiries, delinquency aggregates, enquiry velocity, score history.
    // ------------------------------------------------------------------------------------------

    private static BureauDetail parseDetail(JsonNode report, List<JsonNode> tradelineNodes, LocalDate issuedOn) {
        List<BureauTradeline> tradelines = new ArrayList<>(tradelineNodes.size());
        for (JsonNode node : tradelineNodes) {
            tradelines.add(parseTradeline(node));
        }

        List<JsonNode> enquiryNodes = asList(report.path("INQUIRY-HISTORY").path("HISTORY"));
        List<BureauEnquiry> enquiries = new ArrayList<>();
        for (JsonNode node : enquiryNodes) {
            enquiries.add(parseEnquiry(node));
        }

        BureauDelinquency delinquency = computeDelinquency(tradelineNodes, tradelines);
        BureauEnquiryVelocity velocity = parseVelocity(enquiryNodes, issuedOn);
        BureauScoreHistory scoreHistory = parseScoreHistory(report);

        return new BureauDetail(
                tradelines, tradelines.size(), enquiries, delinquency, velocity, scoreHistory);
    }

    private static BureauTradeline parseTradeline(JsonNode node) {
        String paymentHistory = text(node.path("COMBINED-PAYMENT-HISTORY"));
        return new BureauTradeline(
                text(node.path("CREDIT-GUARANTOR")),
                text(node.path("ACCT-NUMBER")),
                text(node.path("ACCT-TYPE")),
                text(node.path("CREDIT-GRANTOR-TYPE")),
                text(node.path("ACCOUNT-STATUS")),
                formatDate(text(node.path("DISBURSED-DT"))),
                formatDate(text(node.path("CLOSED-DATE"))),
                MoneyParser.indianRupees(text(node.path("CURRENT-BAL"))),
                MoneyParser.indianRupees(text(node.path("OVERDUE-AMT"))),
                MoneyParser.indianRupees(text(node.path("CREDIT-LIMIT"))),
                paymentHistory,
                text(node.path("WRITTEN-OFF-SETTLED-STATUS")),
                MoneyParser.indianRupees(text(node.path("SETTLEMENT-AMT"))),
                PaymentHistory.worstDpd(paymentHistory, Integer.MAX_VALUE));
    }

    private static BureauEnquiry parseEnquiry(JsonNode node) {
        return new BureauEnquiry(
                formatDate(text(node.path("INQUIRY-DATE"))),
                text(node.path("MEMBER-NAME")),
                text(node.path("PURPOSE")),
                MoneyParser.indianRupees(text(node.path("AMOUNT"))),
                null);
    }

    private static BureauEnquiryVelocity parseVelocity(List<JsonNode> enquiryNodes, LocalDate issuedOn) {
        return new BureauEnquiryVelocity(
                countWithin(enquiryNodes, issuedOn, 7),
                countWithin(enquiryNodes, issuedOn, 30),
                countWithin(enquiryNodes, issuedOn, 90),
                countWithin(enquiryNodes, issuedOn, 180));
    }

    private static Integer countWithin(List<JsonNode> enquiryNodes, LocalDate issuedOn, int withinDays) {
        if (issuedOn == null) {
            return null;
        }
        int count = 0;
        for (JsonNode h : enquiryNodes) {
            LocalDate d = parseDate(text(h.path("INQUIRY-DATE")));
            if (d != null && !d.isAfter(issuedOn) && d.isAfter(issuedOn.minusDays(withinDays))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Aggregates over every parsed tradeline. Follows {@link ExperianFactsParser#computeDelinquency}'s
     * null-vs-zero discipline: a counter starts at {@code 0} only once there is at least one tradeline
     * whose relevant field was actually populated to count from — never a manufactured clean record.
     */
    private static BureauDelinquency computeDelinquency(
            List<JsonNode> tradelineNodes, List<BureauTradeline> tradelines) {
        Integer worst12 = null;
        Integer worst24 = null;
        Integer worst36 = null;
        boolean anyDpdKnown = tradelines.stream().anyMatch(t -> t.worstDpdMonths() != null);
        boolean anyPastDueKnown = tradelines.stream().anyMatch(t -> t.amountPastDueRupees() != null);
        Integer ever30Plus = anyDpdKnown ? 0 : null;
        Integer currentlyPastDue = anyPastDueKnown ? 0 : null;
        Integer writtenOffOrSettled = tradelines.isEmpty() ? null : 0;
        String oldestOpen = null;

        for (JsonNode node : tradelineNodes) {
            String history = text(node.path("COMBINED-PAYMENT-HISTORY"));
            worst12 = max(worst12, PaymentHistory.worstDpd(history, 12));
            worst24 = max(worst24, PaymentHistory.worstDpd(history, 24));
            worst36 = max(worst36, PaymentHistory.worstDpd(history, 36));
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

    private static BureauScoreHistory parseScoreHistory(JsonNode report) {
        JsonNode trends = report.path("TRENDS");
        String dates = text(trends.path("DATES"));
        String values = text(trends.path("VALUES"));
        List<BureauScoreHistory.Point> points = new ArrayList<>();
        if (dates != null && values != null) {
            String[] dateParts = dates.split("\\|");
            String[] valueParts = values.split("\\|");
            int n = Math.min(dateParts.length, valueParts.length);
            for (int i = 0; i < n; i++) {
                String asOf = formatDate(dateParts[i].trim());
                Integer scoreValue = parseIntOrNull(valueParts[i].trim());
                if (asOf == null && scoreValue == null) {
                    continue;
                }
                points.add(new BureauScoreHistory.Point(asOf, scoreValue));
            }
        }
        JsonNode derived = report.path("ACCOUNTS-SUMMARY").path("DERIVED-ATTRIBUTES");
        if (points.isEmpty() && derived.isMissingNode()) {
            return null;
        }
        return new BureauScoreHistory(
                points,
                yearsAndMonths(integer(derived.path("LENGTH-OF-CREDIT-HISTORY-YEAR")),
                        integer(derived.path("LENGTH-OF-CREDIT-HISTORY-MONTH"))),
                yearsAndMonths(integer(derived.path("AVERAGE-ACCOUNT-AGE-YEAR")),
                        integer(derived.path("AVERAGE-ACCOUNT-AGE-MONTH"))),
                integer(derived.path("NEW-ACCOUNTS-IN-LAST-SIX-MONTHS")),
                integer(derived.path("NEW-DELINQ-ACCOUNT-IN-LAST-SIX-MONTHS")),
                integer(derived.path("INQURIES-IN-LAST-SIX-MONTHS")));
    }

    private static Integer yearsAndMonths(Integer years, Integer months) {
        if (years == null && months == null) {
            return null;
        }
        return (years == null ? 0 : years) * 12 + (months == null ? 0 : months);
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Tolerates all shapes seen in production for a CRIF sub-section: missing/null (empty list), a JSON
     * array (its elements), or a single JSON object (a one-element list) — CRIF uses a bare object
     * instead of a one-element array whenever exactly one entry exists (confirmed for
     * {@code EMPLOYMENT-DETAILS.EMPLOYMENT-DETAIL}; the same tolerance is applied defensively to
     * {@code RESPONSES.RESPONSE} and {@code ADDRESS-VARIATIONS.VARIATION} since the vendor gives no
     * guarantee they always arrive as arrays either). Mirrors {@code ExperianFactsParser.asList}.
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
