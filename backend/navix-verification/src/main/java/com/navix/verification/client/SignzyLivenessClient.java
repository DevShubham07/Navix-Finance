package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.dbl;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.LivenessCreateUrlRequest;
import com.navix.verification.dto.SignzyDtos.LivenessGetDataRequest;
import com.navix.verification.dto.SignzyDtos.LivenessResult;
import com.navix.verification.dto.SignzyDtos.LivenessSession;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Signzy Liveness Secure — a two-step async passive-liveness (+ optional face-match) flow:
 * {@code POST /api/v3/liveness-secure/createUrl} mints a token + hosted video URL, and
 * {@code POST /api/v3/liveness-secure/getData} fetches the result by that token. DhanBoost's SELFIE step
 * passes the presigned DigiLocker Aadhaar photo as {@code matchImage} for the 1:1 face-match. Runs on the
 * PRODUCTION account ({@link VerificationClientConfig#SIGNZY_PROD_CLIENT}), where liveness is provisioned.
 */
@Component
public class SignzyLivenessClient {

    private static final String CREATE_URL = "/api/v3/liveness-secure/createUrl";
    private static final String GET_DATA = "/api/v3/liveness-secure/getData";

    private final RestClient signzy;

    public SignzyLivenessClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    /** Step 1 — create the liveness session (returns the token + iframe videoUrl to embed). */
    public LivenessSession createUrl(String matchImageUrl) {
        List<String> matchImage = (matchImageUrl == null || matchImageUrl.isBlank())
                ? null : List.of(matchImageUrl);
        JsonNode root = post(signzy, CREATE_URL, new LivenessCreateUrlRequest(matchImage, 0.6));
        return new LivenessSession(
                text(root.path("token")),
                text(root.path("consumerId")),
                text(root.path("videoUrl")));
    }

    /**
     * Step 2 — fetch the liveness + face-match result by token. Signzy returns 404
     * ("Video Verification is not completed till now") until the borrower finishes the journey; that is a
     * normal in-progress state, so it maps to {@link LivenessResult#pending} rather than an error.
     */
    public LivenessResult getData(String token) {
        JsonNode root;
        try {
            root = signzy.post().uri(GET_DATA).body(new LivenessGetDataRequest(token))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return LivenessResult.pending(token);
            }
            throw new VerificationException("HTTP " + e.getStatusCode().value() + " from " + GET_DATA, e);
        }
        if (root == null) {
            throw new VerificationException("Empty response body from " + GET_DATA);
        }
        JsonNode result = root.path("result");
        return new LivenessResult(
                text(result.path("token")),
                true,
                bool(result.path("passiveLiveliness").path("liveness")),
                dbl(result.path("passiveLiveliness").path("score")),
                bool(result.path("faceMatch").path("verified")),
                text(result.path("faceMatch").path("matchPercentage")),
                text(result.path("capturedImage")),
                bool(result.path("status")));
    }
}
