package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.PanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PAN comprehensive verification via Fintrix.
 * Returns identity attributes + Aadhaar-seeding info used in KYC/onboarding.
 */
@Component
public class PanComprehensiveClient {

    private final RestClient fintrix;

    public PanComprehensiveClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /**
     * Verify a PAN and pull comprehensive identity details.
     *
     * <p>DEMO MOCK: returns a deterministic VALID PAN response (name-bearing, Aadhaar linked)
     * without any network call, pending live Fintrix credentials. The injected {@code fintrix}
     * RestClient is intentionally left unused for the demo.
     */
    public PanResponse verify(String pan) {
        return new PanResponse(
                "VALID",
                "RAVI KUMAR",
                "1990-05-14",
                "M",
                Boolean.TRUE,
                "XXXXXXXX1234",
                "12 MG ROAD, BENGALURU, KARNATAKA 560001");
    }
}
