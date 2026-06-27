package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.bool;
import static com.navix.verification.support.FintrixJson.dbl;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.FaceLivenessRequest;
import com.navix.verification.dto.FintrixDtos.FaceLivenessResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * VKYC face-liveness check via Fintrix ({@code vkyc_face_liveness}). Uses the Basic-auth Fintrix
 * client. The caller passes a presigned S3 GET URL to the captured selfie.
 */
@Component
public class FaceLivenessClient {

    private static final String ENDPOINT = "vkyc_face_liveness";

    private final RestClient fintrix;

    public FaceLivenessClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Run liveness on the selfie at {@code pImageUrl} (a presigned GET URL). */
    public FaceLivenessResponse check(String pImageUrl, String clientRef) {
        // clientRef is accepted for call-site symmetry/audit; this endpoint carries no remark field.
        JsonNode root = post(fintrix, ENDPOINT, new FaceLivenessRequest(pImageUrl));
        JsonNode data = root.path("data");
        return new FaceLivenessResponse(
                text(root.path("transaction_id")),
                bool(data.path("is_live")),
                dbl(data.path("liveness_confidence")),
                bool(data.path("person_image_correctly_identified")),
                bool(data.path("multiple_face_detected")),
                bool(data.path("is_face_occluded")));
    }
}
