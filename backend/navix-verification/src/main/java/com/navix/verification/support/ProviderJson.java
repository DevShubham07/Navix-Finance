package com.navix.verification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Small, null-safe helpers shared by every provider HTTP client (Signzy, Digitap, …):
 * <ul>
 *   <li>{@link #post} performs the POST + JsonNode parse and normalises failures into a
 *       {@link VerificationException} (non-2xx, null body, or an envelope reporting an error);</li>
 *   <li>the extractors read a {@link JsonNode} defensively (missing/null/blank &rarr; {@code null},
 *       non-numeric strings &rarr; {@code null}) since several provider envelopes return numbers as
 *       JSON strings.</li>
 * </ul>
 *
 * <p>Provider-neutral (formerly {@code FintrixJson}) and PII-safe by default. The explicitly named
 * temporary raw diagnostic method is the sole pre-go-live exception.
 */
public final class ProviderJson {

    private static final Logger log = LoggerFactory.getLogger(ProviderJson.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CODE_FIELDS = List.of(
            "error_code", "errorCode", "result_code", "resultCode", "code");
    private static final List<String> DETAIL_FIELDS = List.of(
            "message", "error_message", "errorMessage", "description", "detail");

    private ProviderJson() {
    }

    /**
     * POST {@code body} (JSON) to {@code uri} on {@code client}, parse the response as a
     * {@link JsonNode}, and fail closed as a {@link VerificationException} on any error
     * (non-2xx, null body, or an envelope whose {@code status} is {@code "error"}).
     */
    public static JsonNode post(RestClient client, String uri, Object body) {
        return post(client, uri, body, null);
    }

    /**
     * Temporary pre-go-live diagnostic transport for explicitly selected verification providers.
     * This deliberately logs the complete raw JSON request and provider error response, including
     * PII, but never logs HTTP headers or credentials. Remove every {@code TEMP_PII_DEBUG} call
     * before production go-live.
     */
    public static JsonNode postWithRawDiagnostics(
            RestClient client, String uri, Object body, String provider) {
        return post(client, uri, body, provider);
    }

    private static JsonNode post(RestClient client, String uri, Object body, String rawProvider) {
        if (rawProvider != null) {
            log.warn("TEMP_PII_DEBUG provider={} endpoint={} requestPayload={}",
                    rawProvider, uri, rawJson(body));
        }
        JsonNode node;
        try {
            node = client.post().uri(uri).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (rawProvider != null) {
                log.error("TEMP_PII_DEBUG provider={} endpoint={} httpStatus={} responsePayload={}",
                        rawProvider, uri, e.getStatusCode().value(), e.getResponseBodyAsString());
            }
            // Keep the exception metadata safe/redacted even when temporary raw logging is enabled.
            SafeDiagnostic diagnostic = safeDiagnostic(e.getResponseBodyAsString());
            throw new VerificationException(
                    "HTTP " + e.getStatusCode().value() + " from " + uri, e,
                    e.getStatusCode().value(), uri, diagnostic.code(), diagnostic.detail());
        }
        if (node == null) {
            if (rawProvider != null) {
                log.error("TEMP_PII_DEBUG provider={} endpoint={} responsePayload=<empty>",
                        rawProvider, uri);
            }
            throw new VerificationException("Empty response body from " + uri);
        }
        if (rawProvider != null && isProviderErrorEnvelope(node)) {
            log.error("TEMP_PII_DEBUG provider={} endpoint={} responsePayload={}",
                    rawProvider, uri, node);
        }
        if ("error".equalsIgnoreCase(node.path("status").asText(""))) {
            throw new VerificationException("Provider reported error for " + uri);
        }
        return node;
    }

    private static boolean isProviderErrorEnvelope(JsonNode node) {
        String status = node.path("status").asText("");
        if ("error".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "failure".equalsIgnoreCase(status)
                || node.hasNonNull("error")) {
            return true;
        }
        Integer resultCode = integer(node.path("result_code"));
        return resultCode != null && resultCode != 101;
    }

    private static String rawJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception serializationFailure) {
            return String.valueOf(value);
        }
    }

    /** Keep only allowlisted provider error fields and redact identity values before logging. */
    private static SafeDiagnostic safeDiagnostic(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new SafeDiagnostic(null, null);
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            return new SafeDiagnostic(
                    limit(firstValue(root, CODE_FIELDS), 80),
                    limit(redact(firstValue(root, DETAIL_FIELDS)), 180));
        } catch (Exception ignored) {
            return new SafeDiagnostic(null, null);
        }
    }

    private static String firstValue(JsonNode root, List<String> fields) {
        for (String field : fields) {
            String value = trimmed(root.findValue(field));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String redact(String detail) {
        if (detail == null) {
            return null;
        }
        return detail
                .replaceAll("(?i)\\b[A-Z]{5}[0-9]{4}[A-Z]\\b", "[REDACTED]")
                .replaceAll("(?<![0-9])(?:\\+?91[- ]?)?[6-9][0-9]{9}(?![0-9])", "[REDACTED]")
                .replaceAll("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", "[REDACTED]")
                .replaceAll("(?<![0-9])[0-9]{12,18}(?![0-9])", "[REDACTED]");
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record SafeDiagnostic(String code, String detail) {
    }

    /** Null-safe text: missing/JSON-null &rarr; {@code null}; otherwise the node's text value. */
    public static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    /** {@link #text} then {@link String#trim()} (null-safe). */
    public static String trimmed(JsonNode node) {
        String t = text(node);
        return t == null ? null : t.trim();
    }

    /** Null-safe Integer; parses numeric JSON or numeric strings, else {@code null}. */
    public static Integer integer(JsonNode node) {
        String t = text(node);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(t.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null-safe Long; parses numeric JSON or numeric strings, else {@code null}. */
    public static Long lng(JsonNode node) {
        String t = text(node);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(t.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null-safe Double; parses numeric JSON or numeric strings, else {@code null}. */
    public static Double dbl(JsonNode node) {
        String t = text(node);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(t.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null-safe Boolean; accepts JSON booleans or "true"/"false" (and yes/no) strings, else {@code null}. */
    public static Boolean bool(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String t = text(node);
        if ("true".equalsIgnoreCase(t) || "yes".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(t) || "no".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Default a caller-supplied client reference to a short literal when blank. */
    public static String ref(String clientRef) {
        return (clientRef == null || clientRef.isBlank()) ? "navix" : clientRef;
    }
}
