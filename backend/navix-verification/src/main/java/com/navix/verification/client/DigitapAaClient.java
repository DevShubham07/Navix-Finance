package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.postAllowingErrorEnvelope;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.AccountAggregatorPort;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.exception.VerificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Account Aggregator — {@code /bank-data/*} on the svc host. On-demand only; the DB row and
 * polling are the source of truth (no webhook receiver — the callback URL is sent because Digitap
 * requires one). Every call is recorded to {@code provider_api_execution} through {@code ProviderJson}.
 */
@Component
public class DigitapAaClient implements AccountAggregatorPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient rest;

    public DigitapAaClient(@Qualifier(VerificationClientConfig.DIGITAP_AA_CLIENT) RestClient rest) {
        this.rest = rest;
    }

    @Override
    public Link generateUrl(Map<String, Object> body) {
        JsonNode root = post(rest, "/bank-data/generateurl", body);
        String url = text(root.path("url"));
        if (url == null) {
            throw new VerificationException("Digitap AA returned no consent url");
        }
        return new Link(url, text(root.path("request_id")));
    }

    @Override
    public Status statusCheck(String requestId, String txnId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        if (txnId != null) {
            body.put("txn_id", txnId);
        }
        JsonNode root = call("/bank-data/statuscheck", body);
        if (!"success".equalsIgnoreCase(root.path("status").asText(""))) {
            return new Status(root.path("code").asText("UNKNOWN"), txnId);
        }
        JsonNode pick = null;
        for (JsonNode t : root.path("txn_status")) {
            if (txnId == null || txnId.equals(text(t.path("txn_id")))) {
                pick = t;
                break;
            }
        }
        return pick == null ? new Status("TxnNotFound", txnId)
                : new Status(pick.path("code").asText("UNKNOWN"), text(pick.path("txn_id")));
    }

    @Override
    public String retrieveReport(String txnId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txn_id", txnId);
        body.put("report_type", "json");
        body.put("report_subtype", "type3");
        return post(rest, "/bank-data/retrievereport", body).toString();
    }

    @Override
    public Pull initiatePeriodicFetch(String mainTxnId, String templateId) {
        JsonNode root = call("/bank-data/initiate_periodic_fetch",
                Map.of("main_txn_id", mainTxnId, "template_id", templateId));
        boolean ok = "success".equalsIgnoreCase(root.path("status").asText(""));
        return new Pull(ok, root.path("code").asText(null), root.path("msg").asText(null),
                text(root.path("txn_id")));
    }

    /** An error answer (HTTP 4xx or {@code status:error}) is a normal outcome here: return it as an envelope. */
    private JsonNode call(String path, Object body) {
        try {
            return postAllowingErrorEnvelope(rest, path, body);
        } catch (VerificationException e) {
            if (e.httpStatus() == null) {
                throw e; // transport failure / unparseable — not an answer
            }
            return JSON.createObjectNode().put("status", "error")
                    .put("code", e.providerCode() == null ? "HTTP_" + e.httpStatus() : e.providerCode())
                    .put("msg", e.safeDetail());
        }
    }
}
