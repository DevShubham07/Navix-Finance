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
import com.navix.verification.dto.DigitapDtos.UanLookupRequest;
import com.navix.verification.dto.DigitapDtos.UanLookupResponse;
import java.util.Iterator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap UAN employment verification — {@code POST /cv/v3/uan_basic/sync} (svc host). Maps an identity
 * (PAN / mobile / DOB+name) to its EPFO UAN and reads the most recent employment attached to it, which is
 * the independent corroboration of the salary a borrower declares for themselves.
 *
 * <p><b>Why Basic V3 and not Advanced V4.</b> Probed live against production on 2026-08-21 with our own
 * key pair: {@code /cv/v4/uan_advanced/sync}, every other Advanced variant, and every other Basic version
 * all answer <b>412 Precondition Failed</b> — the product is not provisioned on the account. Exactly one
 * UAN product is enabled, {@code /cv/v3/uan_basic/sync}, and it resolves correctly. 412 is not a
 * credential fault (a sibling endpoint on the same key succeeds), not the IP allow-list (that answers
 * 403) and not a malformed request (that answers 400). The evidence and the full probe table are in
 * {@code docs/digitap/UAN_EMPLOYMENT.md}.
 *
 * <p>The cost of Basic is the EPFO PF-filing cross-check: {@code isRecent} / {@code hasPfFilings} /
 * {@code employerConfidenceScore} are Advanced-only and stay null here. Everything else — the summary,
 * the recent employer block, the per-UAN basic details — sits at the identical JSON paths, so this
 * mapping is unchanged from the Advanced one. If Digitap ever provisions V4, only {@link #ENDPOINT}
 * moves and those three fields start arriving.
 *
 * <p><b>Outcome lives in {@code result_code}, not the HTTP status.</b> 101 resolved, 103 no record, 104
 * more than five UANs matched. All three come back 200, and only 101 is billable, so this maps 103/104 to
 * a non-found response rather than an exception — "the EPFO has nothing on you" is an answer, not a
 * fault. A first job, a cash employer and a non-PF establishment all legitimately produce 103.
 *
 * <p>Field/sample source of truth: the vendor's UAN-Basic-V3 entry in
 * {@code docs/digitap/digitap-apis.json} ({@code /apis/27}) and {@code docs/digitap/DigitapGuide.md}.
 * Note the {@code /cv} prefix: the spec writes the path as {@code <base_url>/v3/uan_basic/sync} but the
 * real route carries it.
 */
@Component
public class DigitapUanClient {

    private static final String ENDPOINT = "/cv/v3/uan_basic/sync";

    /** Record resolved. */
    private static final int RESULT_OK = 101;
    /** Identity maps to >5 UANs — nothing resolved. */
    private static final int RESULT_TOO_MANY = 104;

    private final RestClient digitapSvc;

    public DigitapUanClient(
            @Qualifier(VerificationClientConfig.DIGITAP_SVC_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    public UanLookupResponse verify(String pan, String mobile, String dob, String employeeName,
                                      String employerName, String clientRef) {
        // The provider rejects employer_name unless employee_name rides along, so drop it rather than
        // send a request we know is invalid.
        String employer = isBlank(employeeName) ? null : blankToNull(employerName);
        UanLookupRequest request = new UanLookupRequest(
                ref(clientRef), blankToNull(pan), blankToNull(mobile), blankToNull(dob),
                blankToNull(employeeName), employer);

        JsonNode root = post(digitapSvc, ENDPOINT, request);
        Integer resultCode = integer(root.path("result_code"));
        String txnId = text(root.path("request_id"));

        if (resultCode == null || resultCode != RESULT_OK) {
            // 103 (no record) / 104 (too many) / anything else: no employment to report, but the call
            // itself succeeded. The message is the provider's own wording.
            return new UanLookupResponse(txnId, resultCode, trimmed(root.path("message")),
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
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

        return new UanLookupResponse(
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
                bool(summary.path("date_of_exit_marked")),
                blankToNull(trimmed(recent.path("leave_reason"))),
                firstUanSource(result.path("uan_source")),
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

    /**
     * Which identifiers the provider actually matched on, e.g. {@code "pan and mobile"}. Reported per UAN;
     * we resolve a single employment, so the first non-blank entry is the one that produced it.
     */
    private static String firstUanSource(JsonNode uanSource) {
        if (uanSource == null || !uanSource.isArray()) {
            return null;
        }
        Iterator<JsonNode> it = uanSource.elements();
        while (it.hasNext()) {
            String candidate = trimmed(it.next().path("source"));
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
