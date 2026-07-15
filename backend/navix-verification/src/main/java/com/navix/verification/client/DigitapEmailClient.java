package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.dbl;
import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.EmailRequest;
import com.navix.verification.dto.DigitapDtos.EmailResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Email Verification — {@code POST /cv/email_verification/v1} (svc host). Signzy has no email
 * API, so NAVIX's EMAIL step routes straight here. Validates format/domain/SMTP and matches the email
 * to the individual/establishment name.
 */
@Component
public class DigitapEmailClient {

    private static final String ENDPOINT = "/cv/email_verification/v1";

    private final RestClient digitapSvc;

    public DigitapEmailClient(@Qualifier(VerificationClientConfig.DIGITAP_SVC_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    public EmailResponse verify(String email, String individualName,
                                String establishmentName, String clientRef) {
        JsonNode root = post(digitapSvc, ENDPOINT, new EmailRequest(
                ref(clientRef), email, individualName, establishmentName));
        JsonNode result = root.path("result");
        JsonNode summary = result.path("summary");
        String matchedEstablishment = text(result.path("establishment_details")
                .path("matched_establishments").path(0).path("matched_establishment"));
        return new EmailResponse(
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
