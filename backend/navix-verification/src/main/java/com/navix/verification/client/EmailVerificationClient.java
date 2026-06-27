package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.bool;
import static com.navix.verification.support.FintrixJson.dbl;
import static com.navix.verification.support.FintrixJson.integer;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.EmailVerificationRequest;
import com.navix.verification.dto.FintrixDtos.EmailVerificationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * EPFO-backed employer/email verification via Fintrix ({@code cv_email_verification}).
 * Confirms the applicant's email and that their establishment (employer) matches EPFO records.
 */
@Component
public class EmailVerificationClient {

    private static final String ENDPOINT = "cv_email_verification";

    private final RestClient fintrix;

    public EmailVerificationClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Verify email + EPFO employer match. */
    public EmailVerificationResponse verify(String email, String individualName,
                                            String establishmentName, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new EmailVerificationRequest(
                email, ref(clientRef), individualName, establishmentName));
        JsonNode result = root.path("result");
        JsonNode summary = result.path("summary");
        String matchedEstablishment = text(result.path("establishment_details")
                .path("matched_establishments").path(0).path("matched_establishment"));
        return new EmailVerificationResponse(
                text(root.path("request_id")),
                integer(root.path("result_code")),
                bool(summary.path("is_verified")),
                bool(summary.path("is_email_valid")),
                bool(summary.path("is_establishment_matched")),
                bool(summary.path("is_individual_matched")),
                bool(result.path("additional_info").path("is_generic_email")),
                matchedEstablishment,
                dbl(result.path("individual_details").path("score")));
    }
}
