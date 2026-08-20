package com.navix.verification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
 * <p>Provider-neutral (formerly {@code FintrixJson}). Exceptions thrown from here stay redacted, but
 * the call itself is NOT PII-free: {@link ProviderCallLog} records the exact request and response of
 * every call to the admin Provider API dashboard and to the application log. That is deliberate — see
 * {@link ProviderCall}.
 */
public final class ProviderJson {

    private static final Logger log = LoggerFactory.getLogger(ProviderJson.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Sentinel for "no status is tolerated" — every non-2xx is a failure. */
    private static final int NO_TOLERATED_STATUS = -1;

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
     *
     * <p>Every call — whatever its outcome — is handed to {@link ProviderCallLog}, which records the
     * exact request and response to the admin Provider API dashboard and logs them.
     */
    public static JsonNode post(RestClient client, String uri, Object body) {
        return post(client, uri, body, NO_TOLERATED_STATUS);
    }

    /**
     * As {@link #post}, but treats {@code toleratedStatus} as a normal outcome and returns
     * {@code null} instead of throwing. Signzy uses a 404 to mean "the borrower has not finished the
     * liveness journey yet" and "that contract has lapsed" — in-progress/terminal states, not errors.
     *
     * <p>Exists so those two flows can still go through the recorded transport rather than calling
     * {@link RestClient} directly and vanishing from the audit trail.
     */
    public static JsonNode postTolerating(
            RestClient client, String uri, Object body, int toleratedStatus) {
        return post(client, uri, body, toleratedStatus);
    }

    private static JsonNode post(RestClient client, String uri, Object body, int toleratedStatus) {
        String requestJson = rawJson(body);
        ProviderCallLog.logRequest(uri, requestJson);
        long started = System.nanoTime();
        JsonNode node;
        int httpStatus;
        try {
            ResponseEntity<JsonNode> entity =
                    client.post().uri(uri).body(body).retrieve().toEntity(JsonNode.class);
            node = entity.getBody();
            httpStatus = entity.getStatusCode().value();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == toleratedStatus) {
                // An expected state, not a failure — still recorded, so the dashboard shows the poll.
                record(uri, requestJson, e.getResponseBodyAsString(), status, started, null);
                return null;
            }
            record(uri, requestJson, e.getResponseBodyAsString(), status, started,
                    "HTTP " + status + " from " + uri);
            // Keep the exception metadata safe/redacted; the unredacted copy lives in the audit row.
            SafeDiagnostic diagnostic = safeDiagnostic(e.getResponseBodyAsString());
            throw new VerificationException(
                    "HTTP " + e.getStatusCode().value() + " from " + uri, e,
                    e.getStatusCode().value(), uri, diagnostic.code(), diagnostic.detail());
        } catch (RuntimeException transportFailure) {
            // Read/connect timeouts and connection resets never reach the branch above — they used to
            // leave no trace at all beyond a bare RestClientException in the stack.
            record(uri, requestJson, null, null, started, transportFailure.toString());
            throw transportFailure;
        }
        if (node == null) {
            record(uri, requestJson, null, httpStatus, started, "Empty response body from " + uri);
            throw new VerificationException("Empty response body from " + uri);
        }
        // A 2xx carrying an error envelope is still a failed call as far as the audit trail cares.
        record(uri, requestJson, node.toString(), httpStatus, started,
                isProviderErrorEnvelope(node) ? "Provider reported an error envelope" : null);
        if ("error".equalsIgnoreCase(node.path("status").asText(""))) {
            throw new VerificationException("Provider reported error for " + uri);
        }
        return node;
    }

    /**
     * Hand one finished call to the audit trail. {@code error} is {@code null} on success.
     * Recording is best-effort inside {@link ProviderCallLog} and never throws.
     */
    private static void record(String uri, String requestJson, String responseJson,
                               Integer httpStatus, long startedNanos, String error) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        ProviderCallLog.record(new ProviderCall(
                ProviderCallCatalog.providerFor(uri), ProviderCallCatalog.operationFor(uri), uri,
                requestJson, responseJson, httpStatus, durationMs,
                error == null ? ProviderCall.SUCCESS : ProviderCall.FAILED, error));
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
