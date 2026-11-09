package com.navix.loan.payu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * PayU {@code verify_payment}. Never throws: anything but a parsed answer (HTTP error, 429, timeout, bad
 * body) is an empty Optional, the "unknown" result, so a callback or sweep can never fail through here.
 */
@Slf4j
@Component
public class PayuClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PayuProperties props;
    private final RestClient http;

    public PayuClient(PayuProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** What PayU says about one txnid; {@code found=false} means PayU has no such transaction. */
    public record VerifyResult(boolean found, String status, String unmappedstatus, String mihpayid,
                               String transactionAmount, String additionalCharges, String mode, String bankcode,
                               String bankRefNum, String addedon, String errorCode) {
    }

    public Optional<VerifyResult> verify(String txnid) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("key", props.key());
            form.add("command", "verify_payment");
            form.add("var1", txnid);
            form.add("hash", PayuHash.command(props.key(), "verify_payment", txnid, props.salt()));
            String body = http.post().uri(props.commandUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(String.class);
            return parse(body, txnid);
        } catch (RuntimeException e) {
            log.warn("PayU verify failed txnid={} exception={}", txnid, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Optional<VerifyResult> parse(String body, String txnid) {
        try {
            JsonNode d = JSON.readTree(body).path("transaction_details").path(txnid);
            if (d.isMissingNode() || !d.isObject()) {
                return Optional.empty();
            }
            String mihpayid = text(d, "mihpayid");
            boolean found = mihpayid != null && !"Not Found".equalsIgnoreCase(mihpayid);
            return Optional.of(new VerifyResult(found, text(d, "status"), text(d, "unmappedstatus"), mihpayid,
                    text(d, "transaction_amount"), text(d, "additional_charges"), text(d, "mode"),
                    text(d, "bankcode"), text(d, "bank_ref_num"), text(d, "addedon"), text(d, "error_code")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
