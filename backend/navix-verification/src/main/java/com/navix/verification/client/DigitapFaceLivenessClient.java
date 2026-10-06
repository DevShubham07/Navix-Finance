package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.dbl;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.FaceLivenessRequest;
import com.navix.verification.dto.DigitapDtos.FaceLivenessResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Face Liveness v4 — {@code POST /fmfl/v4/face-liveness} (<b>svc</b> host, unlike Face Match
 * which lives on the api host). The PRIMARY check for DhanBoost's SELFIE step: one captured selfie in,
 * a synchronous passive-liveness verdict out. It does <b>not</b> compare the face to any document photo.
 *
 * <p>{@code input_image} takes a public URL or base64; we pass the presigned S3 URL (verified live
 * against production on 2026-10-05, as was the base64 form). Every HTTP 200 is billable. The envelope is
 * {@code {status, req_id, http_status_code, result}} — note {@code req_id}, where Face Match says
 * {@code reqId}. The deepfake fields ({@code is_ai_generated}) are a separately enabled add-on and are
 * absent on this account. Spec: {@code faceliveness v4.pdf} (v1.0, 18 Mar 2026).
 */
@Component
public class DigitapFaceLivenessClient {

    private static final String ENDPOINT = "/fmfl/v4/face-liveness";

    private final RestClient digitapSvc;

    public DigitapFaceLivenessClient(
            @Qualifier(VerificationClientConfig.DIGITAP_SVC_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    /** @param image the captured selfie (public/presigned URL or base64). */
    public FaceLivenessResponse check(String image, String clientRef) {
        JsonNode root = post(digitapSvc, ENDPOINT, new FaceLivenessRequest(ref(clientRef), image));
        JsonNode result = root.path("result");
        return new FaceLivenessResponse(
                text(root.path("req_id")),
                bool(result.path("is_live")),
                dbl(result.path("liveness_confidence")),
                bool(result.path("multiple_face_detected")),
                bool(result.path("is_person_image_blurry")));
    }
}
