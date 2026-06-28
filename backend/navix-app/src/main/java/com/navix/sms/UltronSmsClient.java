package com.navix.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.sms.SmsGateway;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * UltronSMS gateway client (https://ultronsms.com/api/mt/). Sends a single SMS via
 * {@code GET SendSMS} and reads the {@code {ErrorCode, ErrorMessage, JobId}} envelope —
 * {@code "0"}/{@code "000"} is success. Auth is APIKey (if set) else user/password.
 *
 * <p>PII discipline: never logs the message text or recipient number; only the JobId /
 * a masked failure. Follows redirects (the bare http host 301s to https).
 */
@Component
@EnableConfigurationProperties(SmsProperties.class)
public class UltronSmsClient implements SmsGateway {

    private final RestClient rest;
    private final SmsProperties props;

    public UltronSmsClient(SmsProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Send {@code text} to {@code number} (full MSISDN incl. country code, e.g. 91XXXXXXXXXX).
     * Returns the gateway JobId. Throws {@link SmsException} on a non-success code or transport error.
     */
    @Override
    public String send(String number, String text) {
        if (props.mock()) {
            // SMS mock mode (demo/testing): no real send, no DLT. Return a mock reference so all
            // callers — OTP and notifications alike — are short-circuited consistently.
            return "mock-" + UUID.randomUUID();
        }
        try {
            JsonNode resp = rest.get()
                    .uri(uri -> {
                        uri.path("SendSMS");
                        if (props.usesApiKey()) {
                            uri.queryParam("APIKey", props.apiKey());
                        } else {
                            uri.queryParam("user", props.user());
                            uri.queryParam("password", props.password());
                        }
                        uri.queryParam("senderid", props.senderId());
                        uri.queryParam("channel", props.channel());
                        uri.queryParam("DCS", "0");
                        uri.queryParam("flashsms", "0");
                        uri.queryParam("number", number);
                        uri.queryParam("text", text);
                        addIfSet(uri, "route", props.route());
                        addIfSet(uri, "peid", props.peid());
                        addIfSet(uri, "DLTTemplateId", props.dltTemplateId());
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) {
                throw new SmsException("empty SMS gateway response");
            }
            String errorCode = resp.path("ErrorCode").asText("");
            if (!"0".equals(errorCode) && !"000".equals(errorCode)) {
                throw new SmsException("SMS gateway: " + resp.path("ErrorMessage").asText("error"));
            }
            return resp.path("JobId").asText("");
        } catch (RestClientException e) {
            throw new SmsException("SMS gateway transport error", e);
        }
    }

    private static void addIfSet(org.springframework.web.util.UriBuilder uri, String name, String value) {
        if (value != null && !value.isBlank()) {
            uri.queryParam(name, value);
        }
    }
}
