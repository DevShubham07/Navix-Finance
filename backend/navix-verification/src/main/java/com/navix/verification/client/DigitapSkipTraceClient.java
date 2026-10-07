package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.SkipTraceRequest;
import com.navix.verification.dto.DigitapDtos.SkipTraceResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Skip Tracing Lite — {@code POST /enrichment/misc/v1/skip-tracing-lite} (svc host). Given a
 * PAN and/or mobile it returns an identity snapshot plus deduplicated, ranked alternate mobiles, emails
 * and addresses, for reconnecting with a borrower whose contacts have gone stale.
 *
 * <p><b>On demand only, and billable on every HTTP 200</b> — including {@code result_code 103} "No
 * record(s) found". Nothing in the lifecycle calls this; a Collection Head or ADMIN triggers it per
 * customer from the Skip Tracer tab. It deliberately bypasses {@code VerificationPort}: a single
 * vendor with no fallback, same reasoning as the ADMIN workbench calling clients directly.
 *
 * <p>Enabled on our production client id on 2026-10-06 (before that every call was
 * {@code 401 Client Authentication Failed}; a bad PAN now answers the documented {@code 400}). The
 * body is kept verbatim ({@link SkipTraceResponse#rawJson}) because the console renders the
 * provider's own structure. Spec: {@code docs/digitap/} Skip Tracing Lite guide v1.0, 24 Jul 2026.
 */
@Component
public class DigitapSkipTraceClient {

    private static final String ENDPOINT = "/enrichment/misc/v1/skip-tracing-lite";

    /** {@code result_code}: data found. */
    public static final int RESULT_OK = 101;
    /** {@code result_code}: nothing on file for the identifier. Still a 200, still billed. */
    public static final int RESULT_NO_RECORD = 103;

    private final RestClient digitapSvc;

    public DigitapSkipTraceClient(
            @Qualifier(VerificationClientConfig.DIGITAP_SVC_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    /**
     * @param pan       PAN, or null — at least one of pan/mobile is required by the vendor.
     * @param mobile    10-digit mobile, or null.
     * @param name      optional; drives {@code insights.name_match}.
     * @param addresses optional on-file addresses; drive {@code address_match_score/index}.
     */
    public SkipTraceResponse trace(String pan, String mobile, String name, List<String> addresses,
                                   String clientRef) {
        SkipTraceRequest request = new SkipTraceRequest(ref(clientRef), blankToNull(mobile), blankToNull(pan),
                blankToNull(name), addresses == null || addresses.isEmpty() ? null : addresses);
        JsonNode root = post(digitapSvc, ENDPOINT, request);
        return new SkipTraceResponse(
                text(root.path("request_id")),
                integer(root.path("result_code")),
                text(root.path("message")),
                root.toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
