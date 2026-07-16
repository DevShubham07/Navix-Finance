package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.PanRequest;
import com.navix.verification.dto.SignzyDtos.PanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy PAN 206AB Compliance (individual search) — {@code POST /api/v3/pan/compliance-206-individual-search}.
 * A compliance-grade PAN check: masked name + operative status + PAN-Aadhaar link + 206AB specified-person
 * flag. The fields are returned at the TOP LEVEL (not wrapped in {@code result}). Note it does NOT return
 * DOB/gender/address — those are captured from the DigiLocker/Aadhaar step instead.
 */
@Component
public class SignzyPanClient {

    private static final String ENDPOINT = "/api/v3/pan/compliance-206-individual-search";

    private final RestClient signzy;

    public SignzyPanClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    public PanResponse verify(String pan) {
        // maskedName=false → Signzy adds the full name in `unMaskedName` (the account must be entitled).
        JsonNode root = post(signzy, ENDPOINT, new PanRequest(pan, "false"));
        return new PanResponse(
                text(root.path("number")),
                text(root.path("number")),
                trimmed(root.path("entityName")),
                trimmed(root.path("unMaskedName")),
                text(root.path("panAllotmentDate")),
                text(root.path("panAadhaarLinkStatus")),
                bool(root.path("compliant")),
                text(root.path("isSpecified")),
                text(root.path("panStatus")));
    }
}
