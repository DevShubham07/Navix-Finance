package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.EmailVerificationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * EPFO-backed employer/email verification via Fintrix.
 * Confirms the applicant's email and that their establishment (employer) matches EPFO records.
 */
@Component
public class EmailVerificationClient {

    private final RestClient fintrix;

    public EmailVerificationClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /**
     * Verify email + EPFO employer match.
     * TODO: POST email/name/establishment to the Fintrix email-verification endpoint.
     */
    public EmailVerificationResponse verify(String email, String name, String establishment) {
        throw new UnsupportedOperationException("TODO: call Fintrix email/EPFO verification endpoint");
    }
}
