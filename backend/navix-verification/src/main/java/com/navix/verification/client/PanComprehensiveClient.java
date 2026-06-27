package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.bool;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;
import static com.navix.verification.support.FintrixJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.PanRequest;
import com.navix.verification.dto.FintrixDtos.PanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PAN comprehensive verification via Fintrix ({@code pan_comprehensive}).
 * Returns identity attributes + Aadhaar-seeding info used in KYC/onboarding.
 */
@Component
public class PanComprehensiveClient {

    private static final String ENDPOINT = "pan_comprehensive";

    private final RestClient fintrix;

    public PanComprehensiveClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Verify a PAN and pull comprehensive identity details. */
    public PanResponse verify(String pan, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new PanRequest(pan, ref(clientRef)));
        JsonNode data = root.path("data");
        JsonNode address = data.path("address");
        return new PanResponse(
                text(root.path("transaction_id")),
                text(data.path("status")),
                trimmed(data.path("full_name")),
                text(data.path("first_name")),
                text(data.path("middle_name")),
                text(data.path("last_name")),
                text(data.path("dob")),
                text(data.path("gender")),
                text(data.path("category")),
                bool(data.path("aadhaar_linked")),
                text(data.path("masked_aadhaar")),
                text(data.path("phone_number")),
                text(data.path("email")),
                text(data.path("pan_number")),
                text(address.path("full")),
                text(address.path("state")),
                text(address.path("zip")));
    }
}
