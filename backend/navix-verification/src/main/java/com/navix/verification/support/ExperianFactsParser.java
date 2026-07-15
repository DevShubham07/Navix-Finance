package com.navix.verification.support;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.lng;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.verification.BureauReportFacts;

/**
 * Parses a standard Experian credit-report body into the provider-neutral {@link BureauReportFacts}
 * (Categories A/B/C) used by the loan module's credit brief + 1–5★ rating.
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
 */
public final class ExperianFactsParser {

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
        JsonNode addr = appDetails.path("Current_Applicant_Address_Details");
        JsonNode summary = report.path("CAIS_Account").path("CAIS_Summary");
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
                formatDob(text(customer.path("Date_Of_Birth_Applicant"))),
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
                text(report.path("CreditProfileHeader").path("ReportNumber")));
    }

    /** {@code "19850710"} → {@code "1985-07-10"}; anything not 8 digits is returned unchanged. */
    private static String formatDob(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            return t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);
        }
        return t.isBlank() ? null : t;
    }
}
