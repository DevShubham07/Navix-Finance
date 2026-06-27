package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.dbl;
import static com.navix.verification.support.FintrixJson.integer;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.CrifRequest;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CRIF credit bureau pull via Fintrix ({@code individual_crif}). FALLBACK bureau used when Experian
 * is unavailable or returns no hit. Numeric fields arrive as JSON strings — parsed null-safely.
 */
@Component
public class CrifClient {

    private static final String ENDPOINT = "individual_crif";

    private final RestClient fintrix;

    public CrifClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    /** Pull the CRIF credit report for the applicant. */
    public CrifResponse pull(String pan, String name, String mobile, String dob, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new CrifRequest(
                pan, name, mobile, dob == null ? "" : dob, "Y", ref(clientRef)));
        JsonNode data = root.path("data");
        JsonNode summary = data.path("ACCOUNTS-SUMMARY");
        JsonNode primary = summary.path("PRIMARY-ACCOUNTS-SUMMARY");
        JsonNode derived = summary.path("DERIVED-ATTRIBUTES");
        return new CrifResponse(
                text(root.path("transaction_id")),
                text(root.path("status")),
                text(data.path("HEADER").path("STATUS")),
                integer(data.path("SCORES").path("SCORE").path("SCORE-VALUE")),
                integer(primary.path("PRIMARY-ACTIVE-NUMBER-OF-ACCOUNTS")),
                integer(primary.path("PRIMARY-OVERDUE-NUMBER-OF-ACCOUNTS")),
                dbl(primary.path("PRIMARY-CURRENT-BALANCE")),
                integer(derived.path("INQURIES-IN-LAST-SIX-MONTHS")));
    }
}
