package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CRIF credit bureau pull via Fintrix. FALLBACK bureau used when Experian is unavailable
 * or returns no hit.
 */
@Component
public class CrifClient {

    private final RestClient fintrix;

    public CrifClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /**
     * Pull the CRIF credit report for the applicant.
     *
     * <p>DEMO MOCK: returns a deterministic mid-band CRIF report (score 730) without any
     * network call, pending live Fintrix credentials. The injected {@code fintrix} RestClient
     * is intentionally left unused for the demo.
     */
    public CrifResponse pull(String pan, String name, String mobile) {
        return new CrifResponse(
                730,
                new com.navix.verification.dto.FintrixDtos.CrifAccountsSummary(
                        3, 2, 0, 41_000.0, 1),
                "CRIF hit (demo mock)");
    }
}
