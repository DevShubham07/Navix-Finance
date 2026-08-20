package com.navix.app.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navix.verification.support.ProviderCall;
import com.navix.verification.support.ProviderCallContext;
import com.navix.verification.support.ProviderClientRef;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one provider-call audit row in its OWN transaction.
 *
 * <p>{@code REQUIRES_NEW} is essential, not decorative: the provider call happens inside the
 * verification transaction, and if that rolls back we still need the evidence of what we sent and
 * what came back — that is exactly the case an administrator will be investigating.
 *
 * <p>This is a separate bean from {@link ProviderApiExecutionRecorder} on purpose. Calling a
 * {@code @Transactional} method from another method of the SAME bean bypasses the Spring proxy, so
 * {@code REQUIRES_NEW} would silently do nothing and the row would die with the outer rollback.
 */
@Component
@RequiredArgsConstructor
public class ProviderApiExecutionWriter {

    /**
     * Payload cap. A full Experian report is ~100-300 KB and fits; only pathological ones truncate,
     * and for those the first 256 KB is still enough to debug.
     */
    private static final int MAX_PAYLOAD_CHARS = 256 * 1024;

    private static final Duration RETENTION = Duration.ofDays(90);

    /** Only ever used to build the truncation envelope, so a private instance is fine. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderApiExecutionRepository repository;

    /** Short DB timeout so a struggling database cannot add to the borrower's wait. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Long write(ProviderCall call) {
        ProviderApiExecution row = new ProviderApiExecution();
        row.setOperation(call.operation());
        row.setProvider(call.provider());
        row.setEndpoint(call.endpoint());
        row.setRequestJson(storable(call.requestJson(), "{}"));
        row.setResponseJson(storable(call.responseJson(), null));
        row.setStatus(call.status());
        row.setHttpStatus(call.httpStatus());
        row.setErrorMessage(clampError(call.errorMessage()));
        row.setDurationMs(call.durationMs());
        row.setSource(ProviderCallContext.source());
        row.setApplicationId(applicationId(call));
        row.setCheckType(checkType(call));
        row.setRequestId(MDC.get("requestId"));
        row.setExpiresAt(Instant.now().plus(RETENTION));
        return repository.save(row).getId();
    }

    /**
     * The columns are {@code jsonb}, so an over-long payload cannot simply be cut — a truncated JSON
     * document does not parse. Wrap the fragment as a JSON string instead, keeping the row insertable
     * and flagging what happened.
     */
    private static String storable(String payload, String fallback) {
        if (payload == null || payload.isBlank()) {
            return fallback;
        }
        if (payload.length() <= MAX_PAYLOAD_CHARS) {
            return payload;
        }
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("__truncated", true);
        envelope.put("__originalChars", payload.length());
        envelope.put("__body", payload.substring(0, MAX_PAYLOAD_CHARS));
        return envelope.toString();
    }

    /** Provider error bodies can be raw HTML; the column is varchar(2000). */
    private static String clampError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    /**
     * Prefer the application id the servlet filter put on the thread; otherwise recover it from the
     * client ref in the payload, which covers provider callbacks arriving on their own thread.
     */
    private static Long applicationId(ProviderCall call) {
        Long fromContext = ProviderCallContext.applicationId();
        return fromContext != null ? fromContext : ProviderClientRef.applicationId(call.requestJson());
    }

    /**
     * Prefer the check type the verification service put on the thread; otherwise recover it from the
     * client ref we send in every payload ({@code navix-116-BUREAU}).
     */
    private static String checkType(ProviderCall call) {
        String fromContext = ProviderCallContext.checkType();
        return fromContext != null ? fromContext : ProviderClientRef.checkType(call.requestJson());
    }

}
