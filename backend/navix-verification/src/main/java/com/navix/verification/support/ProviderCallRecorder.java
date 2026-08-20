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
}
