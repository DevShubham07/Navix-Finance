package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.AddressRequest;
import com.navix.verification.dto.DigitapDtos.AddressResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Location Services — reverse-geocode a lat/long to an address via
 * {@code POST /ent/v1/address-verification} (api host). Signzy has no address API, so NAVIX's ADDRESS
 * step routes straight here. Response is {@code {code, model:{...}}}.
 */
@Component
public class DigitapAddressClient {

    private static final String ENDPOINT = "/ent/v1/address-verification";

    private final RestClient digitapApi;

    public DigitapAddressClient(@Qualifier(VerificationClientConfig.DIGITAP_API_CLIENT) RestClient digitapApi) {
        this.digitapApi = digitapApi;
    }

    public AddressResponse verify(double lat, double lng, String clientRef) {
        JsonNode root = post(digitapApi, ENDPOINT, new AddressRequest(
                ref(clientRef), Double.toString(lat), Double.toString(lng)));
        JsonNode model = root.path("model");
        return new AddressResponse(
                text(root.path("code")),
                text(model.path("address")),
                text(model.path("pincode")),
                text(model.path("district")),
                text(model.path("state")),
                text(model.path("country")),
                bool(model.path("withInIndia")));
    }
}
