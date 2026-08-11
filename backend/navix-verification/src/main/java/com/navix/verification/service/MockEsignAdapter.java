package com.navix.verification.service;

import com.navix.common.verification.EsignPort;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in eSign provider (revamp.md Phase 3, screen 9) — signs immediately and reports success.
 *
 * <p>It returns no signed PDF, so the stored {@code SIGNED_AGREEMENT} document is the sanction letter
 * as rendered, with the signature reference carried on the {@code ESIGN} verification row rather than
 * stamped into the file. A real provider returns its own signed copy and that copy is stored instead.
 *
 * <p>Registered by {@link EsignConfig}, not by {@code @Component}: the whole point of the seam is
 * that declaring a real {@link EsignPort} bean displaces this one, and {@code @ConditionalOnMissingBean}
 * only honours that on a {@code @Bean} method — on a scanned component it is evaluated against a
 * half-built registry and silently registers nothing.
 */
@Slf4j
public class MockEsignAdapter implements EsignPort {

    public static final String PROVIDER = "MOCK";

    /** Sessions minted this JVM run. Nothing durable hangs off it — the loan module owns the audit. */
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

    @Override
    public EsignSession initiate(EsignRequest request) {
        String clientRef = request.clientRef();
        String sessionId = "mock-esign-" + (clientRef != null ? clientRef : Long.toHexString(System.nanoTime()));
        sessions.put(sessionId, Instant.now());
        // Never log the signer's mobile / documentUrl — the URL is a presigned handle on the borrower's KFS.
        log.info("Mock eSign session {} opened for {}", sessionId,
                request.signer() != null ? request.signer().name() : "unknown signer");
        // signUrl null: the mock signs in place, so the UI shows its own "Sign now" affordance rather
        // than redirecting. A real provider returns a hosted URL here and the page redirects instead.
        return new EsignSession(sessionId, null, PROVIDER);
    }

    @Override
    public EsignResult fetch(String sessionId) {
        Instant openedAt = sessions.get(sessionId);
        return new EsignResult(true, true, openedAt != null ? openedAt : Instant.now(),
                null, sessionId, PROVIDER, null);
    }
}
