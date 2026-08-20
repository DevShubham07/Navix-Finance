package com.navix.verification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.verification.*} block that controls the provider ROUTING order.
 *
 * <p>Also carries the outbound HTTP timeouts, which are operational dials you want to turn from an
 * ECS task-def env var rather than a redeploy — and which must stay consistent with the ALB idle
 * timeout in front of the service.
 *
 * <p>{@code chain} -> {@code NAVIX_VERIFICATION_CHAIN} (default {@code [signzy, digitap]}) — the ordered
 * list of provider ids the {@code RoutingVerificationPort} tries per capability: it calls each in turn,
 * skipping a provider that does not offer the capability and falling through to the next on a failure,
 * returning the first success. Provider ids: {@code signzy}, {@code digitap}.
 */
@ConfigurationProperties(prefix = "navix.verification")
public record VerificationChainProperties(
        List<String> chain,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Integer bureauReadTimeoutSeconds,
        Integer signzyBureauReadTimeoutSeconds
) {

    /**
     * Deliberately the ONLY constructor. A second one makes the canonical constructor ambiguous for
     * {@code @ConfigurationProperties} value binding, which then fails at startup looking for a no-arg
     * constructor — so build test instances with explicit nulls rather than adding a convenience overload.
     */
    private static final int DEFAULT_CONNECT_SECONDS = 5;
    private static final int DEFAULT_READ_SECONDS = 30;
    /** Digitap Credit Analytics is genuinely slow; 30s was cutting off pulls that would have landed. */
    private static final int DEFAULT_BUREAU_READ_SECONDS = 90;
    /**
     * The bureau step is a CHAIN — Signzy Experian, then Signzy CRIF, then Digitap. At the shared 30s
     * that is 150s worst case, past the 120s ALB idle timeout, so the borrower would get a 504 instead
     * of the Digitap result. Capping the two Signzy legs keeps the whole chain inside the budget
     * (12 + 12 + 90 = 114s). They are cheap to cap: both currently fail fast on a 403.
     */
    private static final int DEFAULT_SIGNZY_BUREAU_READ_SECONDS = 12;

    public Duration connectTimeout() {
        return seconds(connectTimeoutSeconds, DEFAULT_CONNECT_SECONDS);
    }

    /** Default read timeout for every provider call that is not a bureau pull. */
    public Duration readTimeout() {
        return seconds(readTimeoutSeconds, DEFAULT_READ_SECONDS);
    }

    /** Read timeout for Digitap Credit Analytics. */
    public Duration bureauReadTimeout() {
        return seconds(bureauReadTimeoutSeconds, DEFAULT_BUREAU_READ_SECONDS);
    }

    /** Read timeout for the two Signzy bureau legs that precede the Digitap fallback. */
    public Duration signzyBureauReadTimeout() {
        return seconds(signzyBureauReadTimeoutSeconds, DEFAULT_SIGNZY_BUREAU_READ_SECONDS);
    }

    private static Duration seconds(Integer configured, int fallback) {
        return Duration.ofSeconds(configured == null || configured <= 0 ? fallback : configured);
    }

    /** The effective chain, defaulting to Signzy → Digitap when unset/blank. */
    public List<String> effectiveChain() {
        return (chain == null || chain.isEmpty()) ? List.of("signzy", "digitap") : chain;
    }
}
