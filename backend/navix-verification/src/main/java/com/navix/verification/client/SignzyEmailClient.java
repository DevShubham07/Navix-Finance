package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.EmailV2Request;
import com.navix.verification.dto.SignzyDtos.EmailV2Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy Email Verification V2 — {@code POST /api/v3/email/verificationV2} on the PRODUCTION account.
 * Runs on {@link VerificationClientConfig#SIGNZY_PROD_CLIENT} (same account as PAN / reverse-geocode /
 * penny-drop). This is DhanBoost's PRIMARY email check; Digitap ({@code /cv/email_verification/v1}) is
 * the fallback the router uses only if this call fails.
 *
 * <p>Request carries just the {@code emailId}. The response wraps everything in {@code result}: a
 * deliverability verdict ({@code validEmail}, {@code status}, {@code mxFound}, {@code mxRecord},
 * {@code smtpProvider}, {@code freeEmail}, {@code didYouMean}) plus optional
 * {@code personalDetails}/{@code companyDetails} enrichment we surface for name-matching. Booleans are
 * returned as JSON strings ({@code "true"}/{@code "false"}), decoded via {@code ProviderJson.bool}.
 */
@Component
public class SignzyEmailClient {

    private static final String ENDPOINT = "/api/v3/email/verificationV2";

    private final RestClient signzy;

    public SignzyEmailClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    public EmailV2Response verify(String email) {
        JsonNode root = post(signzy, ENDPOINT, new EmailV2Request(email));
        JsonNode r = root.path("result");
        return new EmailV2Response(
                text(r.path("emailId")),
                bool(r.path("validEmail")),
                text(r.path("status")),
                bool(r.path("freeEmail")),
                text(r.path("didYouMean")),
                text(r.path("domain")),
                bool(r.path("mxFound")),
                text(r.path("mxRecord")),
                text(r.path("smtpProvider")),
                trimmed(r.path("personalDetails").path("name")),
                trimmed(r.path("companyDetails").path("name")));
    }
}
