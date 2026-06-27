package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.bool;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.AddressVerificationRequest;
import com.navix.verification.dto.FintrixDtos.AddressVerificationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Geo (lat/lng) reverse-geocode + within-India address verification via Fintrix
 * ({@code ent_address_verification}).
 */
@Component
public class AddressVerificationClient {

    private static final String ENDPOINT = "ent_address_verification";

    private final RestClient fintrix;

    public AddressVerificationClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Resolve a lat/lng pair to an address and confirm it is within India. */
    public AddressVerificationResponse verify(double lat, double lng, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new AddressVerificationRequest(
                Double.toString(lat), Double.toString(lng), ref(clientRef)));
        JsonNode model = root.path("model");
        return new AddressVerificationResponse(
                text(root.path("code")),
                text(model.path("address")),
                text(model.path("pincode")),
                text(model.path("district")),
                text(model.path("state")),
                text(model.path("country")),
                bool(model.path("withInIndia")));
    }
}
