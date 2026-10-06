package com.navix.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.whatsapp.WhatsAppGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SmartChat WhatsApp API client (https://smartchatapi.live/portal/Api/). POSTs carry the API token in
 * a {@code token} header (GETs would need it as a query param — we only POST). {@code
 * sender_whatsapp_number} is, despite the name, the RECIPIENT. Success is {@code "status": 200} with a
 * Meta {@code wamid} in {@code request_id}. Never logs the number, params or OTP.
 */
@Component
@EnableConfigurationProperties(WhatsAppProperties.class)
public class SmartChatWhatsAppClient implements WhatsAppGateway {

    private final RestClient rest;
    private final WhatsAppProperties props;

    public SmartChatWhatsAppClient(WhatsAppProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public String sendTemplate(String number, String templateName, List<String> params) {
        Map<String, Object> body = base(number, templateName);
        for (int i = 0; i < params.size(); i++) {
            body.put("parameter_value" + (i + 1), params.get(i));
        }
        return post("send_template_message", body);
    }

    @Override
    public String sendOtp(String number, String code) {
        Map<String, Object> body = base(number, props.otpTemplate());
        body.put("otp_code", code);
        return post("send_template_message_auth", body);
    }

    private static Map<String, Object> base(String number, String templateName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender_whatsapp_number", number);
        body.put("template_name", templateName);
        body.put("broadcast_name", templateName);
        body.put("url", "");
        return body;
    }

    private String post(String path, Map<String, Object> body) {
        if (props.mock()) {
            return "mock-" + UUID.randomUUID();
        }
        if (!props.live()) {
            throw new WhatsAppException("WhatsApp disabled or token not configured");
        }
        try {
            JsonNode resp = rest.post()
                    .uri(path)
                    .header("token", props.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return requestId(resp);
        } catch (RestClientException e) {
            throw new WhatsAppException("WhatsApp gateway transport error: " + e.getMessage());
        }
    }

    /**
     * The message id from a SmartChat envelope, else throws with the provider's message. Observed
     * (2026-09-23): template send {@code {"status":200,"message_id":"wamid…"}}; auth send
     * {@code {"result":"true","request_id":"wamid…"}}; any error {@code {"result":"false","message":"…"}}.
     */
    static String requestId(JsonNode resp) {
        if (resp == null) {
            throw new WhatsAppException("empty WhatsApp gateway response");
        }
        boolean ok = resp.path("status").asInt() == 200 || "true".equals(resp.path("result").asText());
        if (!ok) {
            throw new WhatsAppException("WhatsApp gateway: " + resp.path("message").asText(resp.toString()));
        }
        for (String key : new String[] {"message_id", "request_id"}) {
            if (!resp.path(key).asText("").isEmpty()) {
                return resp.path(key).asText();
            }
        }
        return resp.path("messages").path(0).path("id").asText("");
    }

    public static class WhatsAppException extends RuntimeException {
        public WhatsAppException(String message) {
            super(message);
        }
    }
}
