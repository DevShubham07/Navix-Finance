package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.dbl;
import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.UanAdvancedRequest;
import com.navix.verification.dto.DigitapDtos.UanAdvancedResponse;
import java.util.Iterator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap UAN Advanced Employment V4 — {@code POST /cv/v4/uan_advanced/sync} (svc host). Maps an identity
 * (PAN / mobile / DOB+name) to its EPFO UAN, reads the most recent employment attached to it, then
 * cross-checks that against the establishment's PF filings — which is what catches an employer that has
 * gone quiet or a borrower who left without an exit being marked.
 *
 * <p><b>Outcome lives in {@code result_code}, not the HTTP status.</b> 101 resolved, 103 no record, 104
 * more than five UANs matched. All three come back 200, and only 101 is billable, so this maps 103/104 to
 * a non-found response rather than an exception — "the EPFO has nothing on you" is an answer, not a fault.
 *
 * <p>Field/sample source of truth: the vendor's UAN-Advanced-V4 entry in
 * {@code docs/digitap/digitap-apis.json} (and {@code docs/digitap/DigitapGuide.md} §29). V4 is the
 * vendor-recommended highest-coverage variant. Its response is a strict superset of V3 — every field
 * mapped below is unchanged — so the version bump is behaviour-preserving. The V4-only additions
 * ({@code employment_history[]} per UAN, {@code partial_output}, {@code epfo_details}) are deliberately
 * left unmapped: {@link com.navix.common.verification.VerificationPort.EmploymentCheck} is a flat
 * per-employment record with no Signzy-side equivalent for a stint list, so surfacing them is a follow-up
 * that starts in {@code navix-common}, not here. Note the {@code /cv} prefix: the spec writes the path as
 * {@code <base_url>/v4/uan_advanced/sync} but the real route carries it.
 */
@Component
public class DigitapUanAdvancedClient {

    private static final String ENDPOINT = "/cv/v4/uan_advanced/sync";

    /** Record resolved. */
    private static final int RESULT_OK = 101;
    /** Identity maps to >5 UANs — nothing resolved. */
    private static final int RESULT_TOO_MANY = 104;

    private final RestClient digitapSvc;

    public DigitapUanAdvancedClient(
            @Qualifier(VerificationClientConfig.DIGITAP_SVC_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    public UanAdvancedResponse verify(String pan, String mobile, String dob, String employeeName,
                                      String employerName, String clientRef) {
        // The provider rejects employer_name unless employee_name rides along, so drop it rather than
        // send a request we know is invalid.
        String employer = isBlank(employeeName) ? null : blankToNull(employerName);
        UanAdvancedRequest request = new UanAdvancedRequest(
                ref(clientRef), blankToNull(pan), blankToNull(mobile), blankToNull(dob),
                blankToNull(employeeName), employer);

        JsonNode root = post(digitapSvc, ENDPOINT, request);
        Integer resultCode = integer(root.path("result_code"));
        String txnId = text(root.path("request_id"));

        if (resultCode == null || resultCode != RESULT_OK) {
            // 103 (no record) / 104 (too many) / anything else: no employment to report, but the call
            // itself succeeded. The message is the provider's own wording.
            return new UanAdvancedResponse(txnId, resultCode, trimmed(root.path("message")),
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        JsonNode result = root.path("result");
        JsonNode summary = result.path("summary");
        JsonNode recent = summary.path("recent_employer_data");
        JsonNode epfo = recent.path("epfo");

        // `matching_uan` is the UAN the summary actually resolved to; fall back to the first entry of
        // the `uan` array when it is absent.
        String uan = trimmed(recent.path("matching_uan"));
        if (isBlank(uan)) {
            uan = firstUan(result.path("uan"));
        }
        JsonNode basic = uan == null
                ? result.path("uan_details").path("__absent__")
                : result.path("uan_details").path(uan).path("basic_details");

        return new UanAdvancedResponse(
                txnId,
                resultCode,
                trimmed(root.path("message")),
                bool(summary.path("is_employed")),
                uan,
                integer(summary.path("uan_count")),
                trimmed(recent.path("establishment_name")),
                trimmed(recent.path("establishment_id")),
                trimmed(recent.path("member_id")),
                blankToNull(trimmed(recent.path("date_of_joining"))),
                // An empty date_of_exit is the provider's way of saying "still employed" — normalise it
                // to null so callers don't have to special-case "".
                blankToNull(trimmed(recent.path("date_of_exit"))),
                bool(summary.path("employee_name_match")),
                bool(summary.path("employer_name_match")),
                dbl(recent.path("employer_confidence_score")),
                bool(epfo.path("is_recent")),
                bool(epfo.path("has_pf_filings_details")),
                trimmed(basic.path("name")),
                blankToNull(trimmed(basic.path("date_of_birth"))),
                trimmed(basic.path("gender")));
    }

    private static String firstUan(JsonNode uanArray) {
        if (uanArray == null || !uanArray.isArray()) {
            return null;
        }
        Iterator<JsonNode> it = uanArray.elements();
        while (it.hasNext()) {
            String candidate = trimmed(it.next());
            if (!isBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String blankToNull(String v) {
        return isBlank(v) ? null : v;
    }
}
