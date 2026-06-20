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
     * TODO: call the Fintrix penny-drop endpoint; caller compares fullName against KYC name.
     */
    public PennyDropResponse verify(String accountNumber, String ifsc) {
        throw new UnsupportedOperationException("TODO: call Fintrix penny-drop endpoint");
    }
}
