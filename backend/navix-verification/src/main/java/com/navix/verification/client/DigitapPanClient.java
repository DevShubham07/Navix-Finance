package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.PanRequest;
import com.navix.verification.dto.DigitapDtos.PanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap PAN Details Plus — {@code POST /validation/kyc/v1/pan_details_plus} (svc host). The FALLBACK
 * PAN check, and the richer of the two: unlike Signzy's masked 206AB, it returns the unmasked full name,
 * DOB, gender, Aadhaar-link flag and address. {@code result_code == 101} is success.
 */
@Component
public class DigitapPanClient {

    private static final String ENDPOINT = "/validation/kyc/v1/pan_details_plus";

    private final RestClient digitapSvc;

    public DigitapPanClient(@Qualifier(VerificationClientConfig.DIGITAP_SVC_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    public PanResponse verify(String pan, String clientRef) {
        JsonNode root = post(digitapSvc, ENDPOINT, new PanRequest(ref(clientRef), pan));
        Integer resultCode = integer(root.path("result_code"));
        JsonNode result = root.path("result");
        JsonNode address = result.path("address");
        return new PanResponse(
                text(root.path("request_id")),
                resultCode != null && resultCode == 101,
                trimmed(result.path("fullname")),
                text(result.path("first_name")),
                text(result.path("last_name")),
                text(result.path("dob")),
                text(result.path("gender")),
                bool(result.path("aadhaar_linked")),
                text(result.path("pan_status")),
                text(address.path("state")),
                text(address.path("pincode")));
    }
}
