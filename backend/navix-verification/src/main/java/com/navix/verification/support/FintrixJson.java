package com.navix.verification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.exception.VerificationException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Small, null-safe helpers shared by the Fintrix/DigiLocker HTTP clients:
 * <ul>
 *   <li>{@link #post} performs the POST + JsonNode parse and normalises failures into a
 *       {@link VerificationException} (non-2xx, null body, or {@code status == "error"});</li>
 *   <li>the extractors read a {@link JsonNode} defensively (missing/null/blank &rarr; {@code null},
 *       non-numeric strings &rarr; {@code null}) since several provider envelopes return numbers as
 *       JSON strings.</li>
 * </ul>
 *
 * <p>PII-safe: this layer never logs request or response bodies.
 */
public final class FintrixJson {

    private FintrixJson() {
    }

    /**
     * POST {@code body} (JSON) to {@code uri} on {@code client}, parse the response as a
     * {@link JsonNode}, and fail closed as a {@link VerificationException} on any error.
     */
    public static JsonNode post(RestClient client, String uri, Object body) {
        JsonNode node;
        try {
            node = client.post().uri(uri).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Do not log the body — it may carry PII; surface only the endpoint + status.
            throw new VerificationException(
                    "HTTP " + e.getStatusCode().value() + " from " + uri, e);
        }
        if (node == null) {
            throw new VerificationException("Empty response body from " + uri);
        }
        if ("error".equalsIgnoreCase(node.path("status").asText(""))) {
            throw new VerificationException("Provider reported error for " + uri);
        }
        return node;
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

    /** Null-safe Boolean; accepts JSON booleans or "true"/"false" strings, else {@code null}. */
    public static Boolean bool(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String t = text(node);
        if ("true".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Default a caller-supplied client reference to a short literal when blank. */
    public static String ref(String clientRef) {
        return (clientRef == null || clientRef.isBlank()) ? "navix" : clientRef;
    }
}
