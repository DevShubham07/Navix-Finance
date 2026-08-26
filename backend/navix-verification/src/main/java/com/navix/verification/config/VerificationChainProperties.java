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
 * <p>{@code chain} -> {@code NAVIX_VERIFICATION_CHAIN} (default {@code [fintrix, signzy, digitap]}) —
 * the ordered list of provider ids the {@code RoutingVerificationPort} tries per capability: it calls
 * each in turn, skipping a provider that does not offer the capability and falling through to the next
 * on a failure, returning the first success. Provider ids: {@code fintrix}, {@code signzy},
 * {@code digitap}.
 */
@ConfigurationProperties(prefix = "navix.verification")
public record VerificationChainProperties(
        List<String> chain,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Integer bureauReadTimeoutSeconds,
        Integer signzyBureauReadTimeoutSeconds,
        Integer fintrixBureauReadTimeoutSeconds
) {

    /**
     * Deliberately the ONLY constructor. A second one makes the canonical constructor ambiguous for
     * {@code @ConfigurationProperties} value binding, which then fails at startup looking for a no-arg
     * constructor — so build test instances with explicit nulls rather than adding a convenience overload.
     */
    private static final int DEFAULT_CONNECT_SECONDS = 5;
    private static final int DEFAULT_READ_SECONDS = 30;
    /**
     * Fintrix is now the bureau PRIMARY, tried first; Digitap Credit Analytics is the fallback, reached
     * only when Fintrix is down. The bureau chain is sequential and the ALB idle timeout in front of the
     * service is 120s, so 45 (Fintrix) + 60 (Digitap) = 105s worst case keeps both inside the budget —
     * which is why Digitap's own default was cut from 90s to 60s in the same change.
     */
    private static final int DEFAULT_BUREAU_READ_SECONDS = 60;
    /**
     * Signzy's bureau legs (Experian, CRIF) are retired from the routing chain — Fintrix replaced them —
     * but the clients stay live for the ADMIN provider workbench, so this default is kept short as before.
     */
    private static final int DEFAULT_SIGNZY_BUREAU_READ_SECONDS = 12;
    /** Fintrix {@code /crif_combine} read timeout — see the class-level worst-case budget above. */
    private static final int DEFAULT_FINTRIX_BUREAU_READ_SECONDS = 45;

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

    /** Read timeout for the two Signzy bureau legs (workbench-only; retired from routing). */
    public Duration signzyBureauReadTimeout() {
        return seconds(signzyBureauReadTimeoutSeconds, DEFAULT_SIGNZY_BUREAU_READ_SECONDS);
    }

    /** Read timeout for Fintrix {@code /crif_combine} — the bureau PRIMARY. */
    public Duration fintrixBureauReadTimeout() {
        return seconds(fintrixBureauReadTimeoutSeconds, DEFAULT_FINTRIX_BUREAU_READ_SECONDS);
    }

    private static Duration seconds(Integer configured, int fallback) {
        return Duration.ofSeconds(configured == null || configured <= 0 ? fallback : configured);
    }

    /**
     * The effective chain, defaulting to Signzy → Fintrix → Digitap when unset/blank. Kept in step with
     * {@code application.yml}: tests have no verification block and fall through to this default, so a
     * divergence here would silently exercise a different provider order than production.
     */
    public List<String> effectiveChain() {
        return (chain == null || chain.isEmpty()) ? List.of("signzy", "fintrix", "digitap") : chain;
    }
}
