package com.navix.verification.support;

/**
 * Where {@link ProviderJson} reports a condition that only an operator can clear.
 *
 * <p>The seam exists for the same reason {@link ProviderCallRecorder} does: {@code ProviderJson} is a
 * static helper shared by fifteen clients in a module that knows nothing about Spring events or the
 * notification engine. {@code navix-app} installs the real implementation at startup; everything else
 * (tests, the demo seed, fixture mode) runs against {@link #NOOP}.
 *
 * <p>Deliberately <b>one</b> method. A general "this call failed" hook would mean an in-memory failure
 * counter on the hot path — state that resets on every deploy and takes days to trip on a low-traffic
 * capability like eSign (roughly five calls a day). Sustained failure is detected instead by sweeping
 * the audit table, which is restart-proof and costs nothing per call; see {@code ProviderHealthMonitor}.
 */
public interface ProviderHealthSink {

    ProviderHealthSink NOOP = new ProviderHealthSink() {
    };

    /**
     * A prepaid provider account is at zero, so every call to it will fail until it is topped up.
     *
     * @param provider the provider name as the audit table spells it
     * @param endpoint the URL that answered with the exhausted-balance code
     */
    default void balanceExhausted(String provider, String endpoint) {
    }
}
