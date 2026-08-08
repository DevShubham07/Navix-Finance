package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.AadhaarResponse;
import com.navix.verification.dto.SignzyDtos.DigiLockerCreateUrlRequest;
import com.navix.verification.dto.SignzyDtos.DigiLockerSession;
import com.navix.verification.dto.SignzyDtos.GetEAadhaarRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy DigiLocker <b>v2</b> consent flow. Two calls DhanBoost uses:
 * <ol>
 *   <li>{@code POST /api/v3/digilocker-v2/createUrl} → a DigiLocker OAuth consent {@code url} + a
 *       {@code requestId}. The caller passes a UNIQUE redirect/callback URL per attempt (carrying the
 *       app id + a nonce) to avoid Signzy re-serving a stale, cached consent session.</li>
 *   <li>{@code POST /api/v3/digilocker-v2/geteAadhaar} → the signed e-Aadhaar (parsed demographics +
 *       photo + the signed XML link) by {@code requestId}; 401 until the borrower finishes consent.</li>
 * </ol>
 *
 * <p>Runs on the Signzy <b>production</b> account ({@link VerificationClientConfig#SIGNZY_PROD_CLIENT}) —
 * verified 2026-07-29: digilocker-v2 is entitled there only (the preprod account has no API credits left,
 * and the prod account is not entitled for the retired v1 {@code /api/v3/digilocker/*} pair).
 *
 * <p>Completion is <b>redirect-driven</b>, not poll-driven: the caller finalises off its own DB once the
 * consent callback lands (see {@code ApplicationVerificationService.digilockerComplete}). The PASS gate is
 * {@code x509Data.validAadhaarDSC == "yes"} (Aadhaar document-signer signature valid), surfaced as
 * {@code validDsc}.
 */
@Component
public class SignzyDigiLockerClient {

    private static final String CREATE_URL = "/api/v3/digilocker-v2/createUrl";
    private static final String GET_EAADHAAR = "/api/v3/digilocker-v2/geteAadhaar";

    private final RestClient signzy;

    public SignzyDigiLockerClient(
            @Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    /** Start a DigiLocker consent session. {@code redirectUrl} must be unique per attempt (app+nonce). */
    public DigiLockerSession createUrl(String redirectUrl, boolean signupFlow) {
        JsonNode root = post(signzy, CREATE_URL, new DigiLockerCreateUrlRequest(
                signupFlow,
                true,           // pinlessAuthentication — skip the DigiLocker security PIN prompt
                redirectUrl,
                redirectUrl,
                redirectUrl,
                redirectUrl,    // failure lands on the same callback; it reads our own DB for the outcome
                "kyc",
                true,
                redirectUrl,    // internalId — the per-attempt nonce doubles as our correlation id
                true,
                true,
                true));
        JsonNode result = root.path("result");
        String requestId = text(result.path("requestId"));
        return new DigiLockerSession(requestId, requestId, text(result.path("url")));
    }

    /** Fetch the signed e-Aadhaar for a completed session, by requestId. */
    public AadhaarResponse getEAadhaar(String requestId) {
        JsonNode root = post(signzy, GET_EAADHAAR, new GetEAadhaarRequest(requestId, true, true, true));
        JsonNode result = root.path("result");
        JsonNode split = result.path("splitAddress");
        return new AadhaarResponse(
                requestId,
                trimmed(result.path("name")),
                text(result.path("uid")),
                text(result.path("dob")),
                text(result.path("gender")),
                bool(result.path("x509Data").path("validAadhaarDSC")),
                text(result.path("x509Data").path("subjectName")),
                text(result.path("address")),
                text(split.path("state").path(0).path(0)),
                text(split.path("district").path(0)),
                text(split.path("city").path(0)),
                text(split.path("pincode")),
                text(split.path("country").path(2)),
                text(split.path("addressLine")),
                text(split.path("landMark")),
                text(result.path("photo")),
                text(result.path("aadhaarPdf")),
                text(result.path("aadhaarJpeg")),
                // v2 renamed the signed-XML link; keep the v1 key as a fallback.
                firstText(result, "eAadhaarXmlLink", "xmlFileLink"));
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node.path(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
