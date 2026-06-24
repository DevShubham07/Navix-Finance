package com.navix.verification.client;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.PennyDropResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Penny-drop bank account verification via Fintrix.
 * Acts as the payout name-match gate before disbursement.
 */
@Component
public class PennyDropClient {

    private final RestClient fintrix;

    public PennyDropClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /**
     * Penny-drop verify an account/IFSC and return the registered account holder name.
     *
     * <p>DEMO MOCK: returns a deterministic SUCCESS penny-drop (account exists, name resolved)
     * without any network call, pending live Fintrix credentials. The injected {@code fintrix}
     * RestClient is intentionally left unused for the demo.
     */
    public PennyDropResponse verify(String accountNumber, String ifsc) {
        return new PennyDropResponse(
                "SUCCESS",
                Boolean.TRUE,
                "RAVI KUMAR",
                new com.navix.verification.dto.FintrixDtos.IfscDetails(
                        "HDFC BANK", "MG ROAD", "BENGALURU", "KARNATAKA"));
    }
}
