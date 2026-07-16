package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.dbl;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.GeocodeRequest;
import com.navix.verification.dto.SignzyDtos.GeocodeResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy Reverse Geocoding — {@code POST /api/v3/geocoding/reverse-geocode}. Resolves a lat/long to a
 * postal address (flat response, fields at the top level): {@code address}, {@code city}, {@code state},
 * {@code stateCode}, {@code zipcode}, {@code countryCode}, {@code confidenceScore}. Runs on the Signzy
 * PRODUCTION account (see {@link VerificationClientConfig#SIGNZY_PROD_CLIENT}).
 */
@Component
public class SignzyGeocodeClient {

    private static final String ENDPOINT = "/api/v3/geocoding/reverse-geocode";

    private final RestClient signzy;

    public SignzyGeocodeClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    public GeocodeResponse reverseGeocode(double latitude, double longitude) {
        JsonNode root = post(signzy, ENDPOINT, new GeocodeRequest(
                Double.toString(latitude), Double.toString(longitude), false, false, false));
        return new GeocodeResponse(
                trimmed(root.path("address")),
                trimmed(root.path("city")),
                trimmed(root.path("state")),
                text(root.path("stateCode")),
                text(root.path("zipcode")),
                text(root.path("countryCode")),
                dbl(root.path("confidenceScore")));
    }
}
