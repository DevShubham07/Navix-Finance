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
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy Liveness Secure — a two-step async passive-liveness (+ optional face-match) flow:
 * {@code POST /api/v3/liveness-secure/createUrl} mints a token + iframe video URL, and
 * {@code POST /api/v3/liveness-secure/getData} fetches the result by that token. NAVIX's SELFIE step
 * passes a presigned selfie/ID image URL as {@code matchImage} for the face-match.
 */
@Component
public class SignzyLivenessClient {

    private static final String CREATE_URL = "/api/v3/liveness-secure/createUrl";
    private static final String GET_DATA = "/api/v3/liveness-secure/getData";

    private final RestClient signzy;

    public SignzyLivenessClient(@Qualifier(VerificationClientConfig.SIGNZY_CLIENT) RestClient signzy) {
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

    /** Step 2 — fetch the liveness + face-match result by token. */
    public LivenessResult getData(String token) {
        JsonNode root = post(signzy, GET_DATA, new LivenessGetDataRequest(token));
        JsonNode result = root.path("result");
        return new LivenessResult(
                text(result.path("token")),
                bool(result.path("passiveLiveliness").path("liveness")),
                dbl(result.path("passiveLiveliness").path("score")),
                bool(result.path("faceMatch").path("verified")),
                bool(result.path("status")));
    }
}
