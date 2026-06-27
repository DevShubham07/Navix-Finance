package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.integer;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.ExperianRequest;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Experian credit bureau pull via Fintrix ({@code individual_experian}). PRIMARY bureau for NAVIX
 * risk scoring. Sandbox responses are typically thin-file (no CAIS detail) — that's expected.
 */
@Component
public class ExperianClient {

    private static final String ENDPOINT = "individual_experian";

    private final RestClient fintrix;

    public ExperianClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Pull the Experian credit report for the applicant. */
    public ExperianResponse pull(String pan, String name, String mobile, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new ExperianRequest(
                pan, name, mobile, "Y", ref(clientRef)));
        JsonNode data = root.path("data");
        Integer creditScore = integer(data.path("credit_score"));
        if (creditScore == null) {
            creditScore = integer(data.path("credit_report").path("SCORE").path("FCIREXScore"));
        }
        String message = text(root.path("message"));
        boolean noRecord = creditScore == null
                || (message != null && message.toLowerCase().contains("no record"));
        return new ExperianResponse(
                text(root.path("transaction_id")),
                text(root.path("status")),
                creditScore,
                noRecord,
                message);
    }
}
