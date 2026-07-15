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
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy DigiLocker consent flow. Two calls NAVIX uses:
 * <ol>
 *   <li>{@code POST /api/v3/digilocker/createUrl} → a DigiLocker OAuth consent {@code url} + a
 *       {@code requestId}. The caller passes a UNIQUE redirect/callback URL per attempt (carrying the
 *       app id + a nonce) to avoid Signzy re-serving a stale, cached consent session.</li>
 *   <li>{@code POST /api/v3/digilocker/geteaadhaarwithxml} → the signed e-Aadhaar (parsed demographics +
 *       photo + the signed XML link) by {@code requestId}.</li>
 * </ol>
 *
 * <p>Completion is <b>redirect-driven</b>, not poll-driven: the caller finalises off its own DB once the
 * consent callback lands (see {@code ApplicationVerificationService.digilockerComplete}). The PASS gate is
 * {@code x509Data.validAadhaarDSC == "yes"} (Aadhaar document-signer signature valid), surfaced as
 * {@code validDsc}.
 */
@Component
public class SignzyDigiLockerClient {

    private static final String CREATE_URL = "/api/v3/digilocker/createUrl";
    private static final String GET_EAADHAAR_XML = "/api/v3/digilocker/geteaadhaarwithxml";

    private final RestClient signzy;

    public SignzyDigiLockerClient(@Qualifier(VerificationClientConfig.SIGNZY_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    /** Start a DigiLocker consent session. {@code redirectUrl} must be unique per attempt (app+nonce). */
    public DigiLockerSession createUrl(String redirectUrl, boolean signupFlow) {
        JsonNode root = post(signzy, CREATE_URL, new DigiLockerCreateUrlRequest(
                signupFlow,
                redirectUrl,
                redirectUrl,
                List.of("ADHAR"),
                "kyc",
                true,
                redirectUrl,
                true,
                true));
        JsonNode result = root.path("result");
        String requestId = text(result.path("requestId"));
        return new DigiLockerSession(requestId, requestId, text(result.path("url")));
    }

    /** Fetch the signed e-Aadhaar (with XML) for a completed session, by requestId. */
    public AadhaarResponse getEAadhaar(String requestId) {
        JsonNode root = post(signzy, GET_EAADHAAR_XML, new GetEAadhaarRequest(requestId, true, true));
        JsonNode result = root.path("result");
        JsonNode split = result.path("splitAddress");
        return new AadhaarResponse(
                requestId,
                trimmed(result.path("name")),
                text(result.path("uid")),
                text(result.path("dob")),
                text(result.path("gender")),
                bool(result.path("x509Data").path("validAadhaarDSC")),
                text(result.path("address")),
                text(split.path("state").path(0).path(0)),
                text(split.path("pincode")),
                text(result.path("photo")),
                text(result.path("xmlFileLink")));
    }
}
