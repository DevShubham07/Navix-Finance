package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.bool;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.IfscDetails;
import com.navix.verification.dto.FintrixDtos.PennyDropRequest;
import com.navix.verification.dto.FintrixDtos.PennyDropResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Penny-drop bank account verification via Fintrix ({@code verification_pennydrop}).
 * Acts as the payout name-match gate before disbursement.
 */
@Component
public class PennyDropClient {

    private static final String ENDPOINT = "verification_pennydrop";

    private final RestClient fintrix;

    public PennyDropClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Penny-drop verify an account/IFSC and return the registered account holder name. */
    public PennyDropResponse verify(String accountNumber, String ifsc, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new PennyDropRequest(
                accountNumber, ifsc, true, ref(clientRef)));
        JsonNode data = root.path("data");
        JsonNode ifscNode = data.path("ifsc_details");
        IfscDetails ifscDetails = ifscNode.isMissingNode() || ifscNode.isNull() ? null
                : new IfscDetails(
                        text(ifscNode.path("bank")),
                        text(ifscNode.path("branch")),
                        text(ifscNode.path("city")),
                        text(ifscNode.path("state")),
                        text(ifscNode.path("ifsc")));
        return new PennyDropResponse(
                text(root.path("transaction_id")),
                bool(data.path("status")),
                bool(data.path("account_exists")),
                text(data.path("full_name")),
                ifscDetails);
    }
}
