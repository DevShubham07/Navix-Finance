package com.navix.verification.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static holder for the {@link ProviderHealthSink}, installed once at startup by {@code navix-app} —
 * the same pragmatic seam as {@link ProviderCallLog#setRecorder}, and for the same reason: the caller
 * is a static helper used by fifteen clients.
 *
 * <p>Every dispatch is wrapped so a sink that throws can never turn a provider call into a failure.
 * Raising an alert is strictly less important than the borrower's check completing.
 */
public final class ProviderHealth {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealth.class);

    private static volatile ProviderHealthSink sink = ProviderHealthSink.NOOP;

    private ProviderHealth() {
        // static holder - no instances
    }

    public static void setSink(ProviderHealthSink value) {
        sink = value == null ? ProviderHealthSink.NOOP : value;
    }

    /** Visible for tests, which restore the previous sink in an {@code @AfterEach}. */
    public static ProviderHealthSink sink() {
        return sink;
    }

    /** Never throws. */
    public static void balanceExhausted(String provider, String endpoint) {
        try {
            sink.balanceExhausted(provider, endpoint);
        } catch (Throwable alertFailure) {
            log.warn("PROVIDER_HEALTH alert failed provider={} endpoint={} error={}",
                    provider, endpoint, alertFailure.toString());
        }
    }
}
