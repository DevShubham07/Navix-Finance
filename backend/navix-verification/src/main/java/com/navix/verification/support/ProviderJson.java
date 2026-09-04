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
    /** Fintrix's code for "your prepaid account cannot pay for this call". See the log below. */
    private static final String INSUFFICIENT_BALANCE = "insufficient_balance";

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
        return post(client, uri, body, toleratedStatus, true);
    }

    /**
     * As {@link #post}, but RETURNS an {@code status: "error"} envelope instead of throwing, so the
     * caller can tell a provider's "no record for this person" apart from a provider failure.
     *
     * <p>Fintrix answers a thin file with HTTP 200 and
     * {@code {"status":"error","success":true,"error_message":"No data found in CRIF..."}} — a real
     * answer wearing an error envelope. Left to {@link #post} it throws, the router burns a second
     * billable call falling through to the next bureau, and the row is recorded FAILED and retried
     * forever on every re-run. The caller MUST still throw for envelopes it does not recognise.
     *
     * <p>Non-2xx, a null body and transport failures still fail closed exactly as in {@link #post},
     * and the call is recorded to the audit trail either way.
     */
    public static JsonNode postAllowingErrorEnvelope(RestClient client, String uri, Object body) {
        return post(client, uri, body, NO_TOLERATED_STATUS, false);
    }

    private static JsonNode post(RestClient client, String uri, Object body, int toleratedStatus) {
        return post(client, uri, body, toleratedStatus, true);
    }

    private static JsonNode post(RestClient client, String uri, Object body, int toleratedStatus,
                                 boolean throwOnErrorEnvelope) {
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
            if (INSUFFICIENT_BALANCE.equalsIgnoreCase(diagnostic.code())) {
                // A prepaid provider account at zero fails EVERY call, and nothing else says so:
                // production sat at zero balance for 11.5 hours in Aug 2026 and 106 applications lost
                // their bureau pull behind a generic HTTP_402. Logged at ERROR because it is an
                // operational outage with a one-step fix (top up), not a per-borrower problem.
                log.error("PROVIDER_BALANCE_EXHAUSTED endpoint={} — every call to this provider will "
                        + "keep failing until the account is topped up", uri);
            }
            throw new VerificationException(
                    "HTTP " + e.getStatusCode().value() + " from " + uri, e,
                    e.getStatusCode().value(), uri, diagnostic.code(), diagnostic.detail());
        } catch (RuntimeException transportFailure) {
            // Read/connect timeouts, connection resets and unreadable response bodies never reach the
            // branch above — they used to leave no trace at all beyond a bare RestClientException.
            record(uri, requestJson, null, null, started, transportFailure.toString());
            // This MUST be a VerificationException. RoutingVerificationPort.route() catches only that
            // and CapabilityNotSupportedException, so rethrowing the raw RestClientException here
            // propagated PAST the router and aborted the entire provider chain: the remaining
            // providers were never called and the answer was lost outright (25 applications in the
            // Sep-2026 pending-queue audit, where Signzy was leg 1 and Digitap/Fintrix never ran).
            // The 6-arg constructor carries the endpoint so route()'s fall-through log still names it;
            // httpStatus and providerCode stay null because the call never got a response to read.
            throw new VerificationException("Transport failure calling " + uri, transportFailure,
                    null, uri, null, null);
        }
        if (node == null) {
            record(uri, requestJson, null, httpStatus, started, "Empty response body from " + uri);
            throw new VerificationException("Empty response body from " + uri);
        }
        // A 2xx carrying an error envelope is still a failed call as far as the audit trail cares.
        record(uri, requestJson, node.toString(), httpStatus, started,
                isProviderErrorEnvelope(node) ? "Provider reported an error envelope" : null);
        if (throwOnErrorEnvelope && "error".equalsIgnoreCase(node.path("status").asText(""))) {
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
