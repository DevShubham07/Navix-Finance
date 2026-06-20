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
     * TODO: call the Fintrix Experian endpoint and map score + tradelines.
     */
    public ExperianResponse pull(String pan, String name, String mobile) {
        throw new UnsupportedOperationException("TODO: call Fintrix Experian bureau endpoint");
    }
}
