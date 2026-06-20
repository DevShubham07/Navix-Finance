package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.AddressVerificationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Geo (lat/lng) reverse-geocode + within-India address verification via Fintrix.
 */
@Component
public class AddressVerificationClient {

    private final RestClient fintrix;

    public AddressVerificationClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /**
     * Resolve a lat/lng pair to an address and confirm it is within India.
     * TODO: call the Fintrix address/geo verification endpoint.
     */
    public AddressVerificationResponse verify(double lat, double lng) {
        throw new UnsupportedOperationException("TODO: call Fintrix address verification endpoint");
    }
}
