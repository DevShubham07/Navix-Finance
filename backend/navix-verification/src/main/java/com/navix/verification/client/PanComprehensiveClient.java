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
     * TODO: POST the PAN to the Fintrix pan-comprehensive endpoint and map the envelope to PanResponse.
     */
    public PanResponse verify(String pan) {
        throw new UnsupportedOperationException("TODO: call Fintrix PAN comprehensive endpoint");
    }
}
