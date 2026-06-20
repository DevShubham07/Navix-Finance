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
     * TODO: call the Fintrix CRIF endpoint and map score + accounts summary.
     */
    public CrifResponse pull(String pan, String name, String mobile) {
        throw new UnsupportedOperationException("TODO: call Fintrix CRIF bureau endpoint");
    }
}
