package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Experian credit bureau pull via Fintrix. PRIMARY bureau for NAVIX risk scoring.
 */
@Component
public class ExperianClient {

    private final RestClient fintrix;

    public ExperianClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /**
     * Pull the Experian credit report for the applicant.
     *
     * <p>DEMO MOCK: returns a deterministic mid-band Experian report (score 742, one active
     * tradeline) without any network call, pending live Fintrix credentials. The injected
     * {@code fintrix} RestClient is intentionally left unused for the demo.
     */
    public ExperianResponse pull(String pan, String name, String mobile) {
        return new ExperianResponse(
                742,
                java.util.List.of(new com.navix.verification.dto.FintrixDtos.Tradeline(
                        "CREDIT_CARD", "HDFC BANK", 25_000.0, 0.0, "ACTIVE")),
                "Experian hit (demo mock)");
    }
}
