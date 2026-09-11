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
 * <p>{@code chain} -> {@code NAVIX_VERIFICATION_CHAIN} (default {@code [signzy, digitap, fintrix]}) —
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
        Integer fintrixBureauReadTimeoutSeconds,
        Integer digitapCrifReadTimeoutSeconds,
        Boolean bureauNoHitFallThrough
) {

    /**
     * Deliberately the ONLY constructor. A second one makes the canonical constructor ambiguous for
     * {@code @ConfigurationProperties} value binding, which then fails at startup looking for a no-arg
     * constructor — so build test instances with explicit nulls rather than adding a convenience overload.
     */
    private static final int DEFAULT_CONNECT_SECONDS = 5;
    private static final int DEFAULT_READ_SECONDS = 30;
    /**
     * The bureau chain is sequential and the ALB idle timeout in front of the service is 120s, so every
     * leg has to fit inside that budget together. With {@code digitap-crif} OFF (its endpoint still 401s)
     * the live worst case is Digitap Experian 45 + Fintrix CRIF 45 = <b>90s</b> read, <b>100s</b> once the
     * two 5s connect timeouts are counted — 20s of headroom. Signzy's bureau leg throws
     * {@code CapabilityNotSupportedException} without opening a socket, so it costs nothing.
     *
     * <p><b>Before re-enabling {@code digitap-crif}, raise the ALB idle timeout.</b> That third leg takes
     * the budget to 45 + 20 + 45 = 110s read and <b>125s</b> with connect timeouts — over the 120s limit.
     * The older comment here quoted 110s as if it fitted, because it never counted the connect timeouts.
     */
    private static final int DEFAULT_BUREAU_READ_SECONDS = 45;
    /**
     * Signzy's bureau legs (Experian, CRIF) are retired from the routing chain — Fintrix replaced them —
     * but the clients stay live for the ADMIN provider workbench, so this default is kept short as before.
     */
    private static final int DEFAULT_SIGNZY_BUREAU_READ_SECONDS = 12;
    /** Fintrix {@code /crif_combine} read timeout — see the class-level worst-case budget above. */
    private static final int DEFAULT_FINTRIX_BUREAU_READ_SECONDS = 45;
    /**
     * Digitap {@code /credit_analytics/v2/cf} read timeout — the middle bureau leg. Deliberately the
     * tightest of the three: it sits between two legs that must still get their full budget, and it is
     * the one we have never measured (the endpoint has not authenticated yet). Re-tune from observed
     * latency once it is live; if it needs more than 20s, raise the ALB idle timeout rather than
     * squeezing Experian further.
     */
    private static final int DEFAULT_DIGITAP_CRIF_READ_SECONDS = 20;

    public Duration connectTimeout() {
        return seconds(connectTimeoutSeconds, DEFAULT_CONNECT_SECONDS);
    }

    /** Default read timeout for every provider call that is not a bureau pull. */
    public Duration readTimeout() {
        return seconds(readTimeoutSeconds, DEFAULT_READ_SECONDS);
    }

    /** Read timeout for Digitap Credit Analytics (Experian) — the last-resort bureau leg. */
    public Duration bureauReadTimeout() {
        return seconds(bureauReadTimeoutSeconds, DEFAULT_BUREAU_READ_SECONDS);
    }

    /** Read timeout for Digitap Credit Analytics CRIF — the middle bureau leg. */
    public Duration digitapCrifReadTimeout() {
        return seconds(digitapCrifReadTimeoutSeconds, DEFAULT_DIGITAP_CRIF_READ_SECONDS);
    }

    /** Read timeout for the two Signzy bureau legs (workbench-only; retired from routing). */
    public Duration signzyBureauReadTimeout() {
        return seconds(signzyBureauReadTimeoutSeconds, DEFAULT_SIGNZY_BUREAU_READ_SECONDS);
    }

    /** Read timeout for Fintrix {@code /crif_combine} — the bureau FALLBACK behind Digitap. */
    public Duration fintrixBureauReadTimeout() {
        return seconds(fintrixBureauReadTimeoutSeconds, DEFAULT_FINTRIX_BUREAU_READ_SECONDS);
    }

    private static Duration seconds(Integer configured, int fallback) {
        return Duration.ofSeconds(configured == null || configured <= 0 ? fallback : configured);
    }

    /**
     * The effective chain, defaulting to Signzy → Digitap → Fintrix when unset/blank. Kept in step with
     * {@code application.yml}: tests have no verification block and fall through to this default, so a
     * divergence here would silently exercise a different provider order than production.
     */
    public List<String> effectiveChain() {
        return (chain == null || chain.isEmpty()) ? List.of("signzy", "digitap", "fintrix") : chain;
    }

    /**
     * Whether a bureau no-hit falls through to the next provider instead of ending the chain.
     *
     * <p>Defaults to TRUE. Digitap (Experian) leads the bureau chain, so without this a thin file that
     * Experian has never seen would come back {@code noRecord} and CRIF — a genuinely different data
     * source — would never get a look, silently narrowing coverage. The cost is a second billable pull
     * on every genuinely thin file; set the property false to restore the older "first answer wins,
     * no-hit included" behaviour.
     */
    public boolean bureauNoHitFallThroughEnabled() {
        return bureauNoHitFallThrough == null || bureauNoHitFallThrough;
    }
}
