package com.navix.verification.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single place every outbound provider call is observed: it writes the call to the
 * {@code provider_api_execution} audit table (via the injected {@link ProviderCallRecorder}) AND
 * emits a {@code PROVIDER_CALL} log line carrying the raw request/response.
 *
 * <p><b>These log lines contain PII</b> (PAN, date of birth, mobile, name, bureau consent OTP) — a
 * deliberate product decision so a failing integration can be diagnosed straight from CloudWatch.
 * This replaces the old {@code TEMP_PII_DEBUG} lines, which only 4 of the 15 clients opted into; it
 * is now every call, and it is permanent rather than pre-go-live scaffolding.
 *
 * <p>The request is logged BEFORE the call and the response after, so a call that hangs or dies
 * mid-flight still leaves evidence of what we sent.
 *
 * <p>Nothing here may disturb the caller: a recorder that throws is swallowed, because failing to
 * write an audit row must never turn a successful verification into a failed one.
 */
public final class ProviderCallLog {

    private static final Logger log = LoggerFactory.getLogger("com.navix.provider");

    /**
     * CloudWatch rejects events over 256 KB, so a full Experian report cannot go out verbatim
     * anyway. The log line is capped and the untruncated payload lives in the dashboard row that
     * {@code executionId} points at.
     */
    private static final int LOG_PAYLOAD_LIMIT = 100_000;

    private static volatile ProviderCallRecorder recorder = ProviderCallRecorder.NOOP;

    private ProviderCallLog() {
        // static holder - no instances
    }

    /**
     * Installed once at startup by {@code navix-app}, which owns the table. Static injection is the
     * pragmatic seam here: {@link ProviderJson} is a static helper used by 15 clients, and turning
     * it into a bean would be a far larger and riskier refactor than this one write.
     */
    public static void setRecorder(ProviderCallRecorder value) {
        recorder = value == null ? ProviderCallRecorder.NOOP : value;
    }

    /** Log what we are about to send, before the call can hang or blow up. */
    public static void logRequest(String endpoint, String requestJson) {
        log.info("PROVIDER_CALL start provider={} operation={} endpoint={} applicationId={} requestPayload={}",
                ProviderCallCatalog.providerFor(endpoint), ProviderCallCatalog.operationFor(endpoint),
                endpoint, ProviderCallContext.applicationId(), clamp(requestJson));
    }

    /** Persist the completed call and log its outcome. Never throws. */
    public static void record(ProviderCall call) {
        Long executionId = null;
        try {
            executionId = recorder.record(call);
            ProviderCallContext.setLastExecutionId(executionId);
        } catch (Throwable recordingFailure) {
            // An audit-trail failure must not surface as a verification failure.
            log.warn("PROVIDER_CALL could not be recorded provider={} endpoint={} error={}",
                    call.provider(), call.endpoint(), recordingFailure.toString());
        }
        if (call.failed()) {
            log.error("PROVIDER_CALL failed provider={} operation={} endpoint={} httpStatus={} "
                            + "durationMs={} applicationId={} executionId={} error={} responsePayload={}",
                    call.provider(), call.operation(), call.endpoint(), call.httpStatus(),
                    call.durationMs(), ProviderCallContext.applicationId(), executionId,
                    call.errorMessage(), clamp(call.responseJson()));
        } else {
            log.info("PROVIDER_CALL ok provider={} operation={} endpoint={} httpStatus={} "
                            + "durationMs={} applicationId={} executionId={} responsePayload={}",
                    call.provider(), call.operation(), call.endpoint(), call.httpStatus(),
                    call.durationMs(), ProviderCallContext.applicationId(), executionId,
                    clamp(call.responseJson()));
        }
    }

    private static String clamp(String payload) {
        if (payload == null) {
            return "<empty>";
        }
        return payload.length() <= LOG_PAYLOAD_LIMIT
                ? payload
                : payload.substring(0, LOG_PAYLOAD_LIMIT) + "…<truncated, see dashboard row>";
    }
}
