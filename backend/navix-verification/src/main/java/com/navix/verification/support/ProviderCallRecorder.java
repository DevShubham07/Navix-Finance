package com.navix.verification.support;

/**
 * Port for persisting a {@link ProviderCall}. Implemented in {@code navix-app} (the module that owns
 * the {@code provider_api_execution} table) and injected into {@link ProviderCallLog} at startup —
 * {@code navix-app} depends on {@code navix-verification}, never the reverse, so the dependency has
 * to be inverted through this interface.
 *
 * <p>The default is a no-op so this module still works standalone (unit tests, any consumer that
 * does not care about the audit trail).
 */
@FunctionalInterface
public interface ProviderCallRecorder {

    /** No-op recorder — the default until {@code navix-app} wires the real one in. */
    ProviderCallRecorder NOOP = call -> null;

    /**
     * Persist the call and return the stored row id (or {@code null} if nothing was stored).
     * Implementations MUST NOT let a storage failure escape into the caller's flow.
     */
    Long record(ProviderCall call);

    /**
     * Flip an already-stored row to FAILED — for a call the transport recorded as a normal answer but
     * the client then judged a failure.
     *
     * <p>{@code postAllowingErrorEnvelope} exists so a client can read a provider's "error" envelope
     * as an ANSWER (a thin credit file, no EPFO record). The transport cannot know which of those a
     * given client will accept, so it records the call as served and the handful of clients that
     * reject one say so here. Default no-op, like {@link #record}.
     */
    default void markFailed(Long executionId, String error) {
        // no-op unless navix-app wires a real recorder in
    }
}
