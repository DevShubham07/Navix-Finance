package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.dbl;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.FaceMatchRequest;
import com.navix.verification.dto.DigitapDtos.FaceMatchResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Face Match — {@code POST /fmfl/v2/face-match} (api host). The FALLBACK for DhanBoost's SELFIE step
 * when Signzy Liveness Secure is unavailable. Digitap's server API is a face <i>match</i> (selfie vs
 * document photo), not standalone passive liveness (that is Digitap's client-side FL Web SDK), so with
 * only the selfie image available this yields a degraded quality/identification signal
 * ({@code same_face_confidence}) rather than a true liveness score. Response is {@code {status, result}}.
 */
@Component
public class DigitapFaceMatchClient {

    private static final String ENDPOINT = "/fmfl/v2/face-match";

    private final RestClient digitapApi;

    public DigitapFaceMatchClient(@Qualifier(VerificationClientConfig.DIGITAP_API_CLIENT) RestClient digitapApi) {
        this.digitapApi = digitapApi;
    }

    /**
     * @param personImage the live selfie (URL or base64).
     * @param cardImage   the document photo to match against (may be {@code null} when unavailable).
     */
    public FaceMatchResponse match(String personImage, String cardImage, String clientRef) {
        JsonNode root = post(digitapApi, ENDPOINT,
                new FaceMatchRequest(personImage, cardImage, ref(clientRef)));
        JsonNode result = root.path("result");
        return new FaceMatchResponse(
                text(root.path("reqId")),
                bool(result.path("is_same_face")),
                dbl(result.path("same_face_confidence")),
                bool(result.path("is_person_image_blurry")));
    }
}
