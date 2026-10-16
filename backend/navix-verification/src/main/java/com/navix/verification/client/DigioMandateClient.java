package com.navix.verification.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navix.common.exception.BusinessException;
import com.navix.common.verification.MandatePort;
import com.navix.verification.config.DigioProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Digio NACH eMandate over HTTP Basic. Constructed by {@code DigioConfig} (which validates the settings).
 *
 * <p><b>Casing:</b> the live API answers {@code snake_case} ({@code ifsc_prefix}, {@code api_mandate}), not
 * the camelCase its published spec documents (docs/digio/13-live-verification-log.md section 2). Responses
 * are read as a {@link JsonNode} tree, which also tolerates the undocumented fields Digio adds without
 * notice. Request and response vocabularies differ too: we write {@code frequency: "Adhoc"} and read back
 * {@code "ADHO"}; nothing here maps that field, so neither direction can break the other.
 *
 * <p><b>Errors:</b> business rejections arrive as HTTP 500 {@code {code:"SYSTEM_ERROR"}} (log section 7), so the
 * outcome is classified on the body, never the status, and {@code details} (Digio support's only handle) is
 * always logged. Bodies are never logged: responses carry the full account number and the
 * {@code authentication_url}.
 */
public class DigioMandateClient implements MandatePort {

    private static final Logger log = LoggerFactory.getLogger(DigioMandateClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_EXPIRY_DAYS = 7;

    private final RestClient http;
    private final DigioProperties props;

    public DigioMandateClient(DigioProperties props, RestClient.Builder builder) {
        this.props = props;
        String token = java.util.Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public MandateSession register(MandateRequest r) {
        ObjectNode data = JSON.createObjectNode();
        data.put("management_category", "L001");
        data.put("is_recurring", true);
        data.put("frequency", "Adhoc");
        data.put("first_collection_date", r.firstCollectionDate().toString());
        if (r.finalCollectionDate() != null) {
            data.put("final_collection_date", r.finalCollectionDate().toString());
        }
        // Rupees, decimal; the only paise-to-rupee conversion in the system. collection_amount is omitted on
        // purpose: a MAXIMUM-type mandate is legal and the debit varies with any late penalty.
        data.put("maximum_amount", BigDecimal.valueOf(r.maximumAmountPaise(), 2));
        data.put("customer_name", safeName(r.customerName()));
        data.put("customer_account_number", r.accountNumber());
        data.put("customer_account_type", "SAVINGS");
        data.put("destination_bank_id", r.ifsc());
        data.put("destination_bank_name", r.bankName());
        data.put("customer_ref_number", r.customerRefNumber());
        data.put("customer_mobile", r.mobile());

        ObjectNode body = JSON.createObjectNode();
        body.put("corporate_config_id", props.corporateConfigId());
        body.put("mandate_type", "CREATE");
        body.put("auth_mode", "API");
        body.put("customer_identifier", r.mobile());
        body.put("notify_customer", "False");
        body.put("include_authentication_url", true);
        body.put("expire_in_days", props.expireInDays() != null ? props.expireInDays() : DEFAULT_EXPIRY_DAYS);
        body.set("mandate_data", data);

        JsonNode root = call("create_form", http.post().uri("/v3/client/mandate/create_form")
                .contentType(MediaType.APPLICATION_JSON).body(body.toString()));
        return new MandateSession(text(root.path("id")), text(root.path("state")),
                text(root.path("authentication_url")));
    }

    @Override
    public MandateResult fetch(String digioMandateId) {
        return result(call("fetch", http.get().uri("/v3/client/mandate/{id}", digioMandateId)));
    }

    @Override
    public Optional<MandateResult> findByRef(String customerRefNumber) {
        // A miss may come as 404 (shape unverified). "No mandate" must stay distinguishable from "error"
        // for timeout recovery, so a 404 is empty and everything else still throws.
        JsonNode root = call("by_crn", http.get().uri("/v3/client/mandate/by_crn/{crn}", customerRefNumber), true);
        JsonNode first = root.isArray() ? root.path(0) : root;
        return root == null || first.isMissingNode() || first.isNull() || first.isEmpty() ? Optional.empty()
                : Optional.of(result(first));
    }

    @Override
    public List<BankCapability> liveBanks() {
        JsonNode root = call("banks", http.get().uri("/v3/client/mandate/banks?mode=API"));
        List<BankCapability> out = new ArrayList<>();
        for (JsonNode b : root) {
            List<String> subs = new ArrayList<>();
            b.path("allowed_auth_sub_type").forEach(s -> subs.add(s.asText()));
            out.add(new BankCapability(text(b.path("ifsc_prefix")), b.path("api_mandate").asBoolean(false), subs));
        }
        return out;
    }

    private static MandateResult result(JsonNode m) {
        JsonNode bank = m.path("bank_details");
        // The newest bank response report carries the accept/reject verdict and the UMRN.
        JsonNode res = bank.path("res_report").path(0);
        String umrn = text(m.path("umrn"));
        if (umrn == null) {
            umrn = text(res.path("umrn"));
        }
        String rejectReason = text(res.path("reject_reason"));
        if (rejectReason == null) {
            rejectReason = text(m.path("npci_auth_reject_reason"));
        }
        return new MandateResult(text(m.path("id")), text(m.path("state")), text(bank.path("state")), umrn,
                text(m.path("auth_sub_mode")), text(res.path("reject_code")), rejectReason,
                instant(text(bank.path("authenticated_at"))));
    }

    private JsonNode call(String op, RestClient.RequestHeadersSpec<?> spec) {
        return call(op, spec, false);
    }

    private JsonNode call(String op, RestClient.RequestHeadersSpec<?> spec, boolean notFoundIsMissing) {
        try {
            byte[] bytes = spec.retrieve().body(byte[].class);
            return bytes == null || bytes.length == 0 ? JSON.missingNode() : JSON.readTree(bytes);
        } catch (RestClientResponseException e) {
            if (notFoundIsMissing && e.getStatusCode().value() == 404) {
                return JSON.missingNode();
            }
            throw classify(op, e);
        } catch (java.io.IOException e) {
            throw new BusinessException("DIGIO_UNREADABLE", "Digio " + op + " returned an unreadable body");
        }
    }

    /** Classify on the body, never the status: Digio sends business errors as HTTP 500. */
    private static BusinessException classify(String op, RestClientResponseException e) {
        String code = null;
        String message = null;
        String details = null;
        try {
            JsonNode b = JSON.readTree(e.getResponseBodyAsByteArray());
            code = text(b.path("code"));
            message = text(b.path("message"));
            details = text(b.path("details"));
        } catch (Exception ignored) {
            // non-JSON body (a proxy error page): fall through to the status-based code
        }
        // details is the exception id Digio support needs; it is the only handle on a SYSTEM_ERROR.
        log.warn("Digio {} failed: http={} code={} message={} details={}",
                op, e.getStatusCode().value(), code, message, details);
        return new BusinessException(code != null ? code : "DIGIO_HTTP_" + e.getStatusCode().value(),
                message != null ? message : "Digio " + op + " failed");
    }

    /** Digio rejects apostrophes and hyphens (and caps at 40); the name should still resemble the bank's. */
    private static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[^0-9A-Za-z\\s.]", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 40 ? s.substring(0, 40).trim() : s;
    }

    private static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (Exception e2) {
                return null; // ponytail: unknown timestamp format; confirm against a real authenticated mandate
            }
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }
}
