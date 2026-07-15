package com.navix.verification.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.verification.*} block that controls the provider ROUTING order.
 *
 * <p>{@code chain} -> {@code NAVIX_VERIFICATION_CHAIN} (default {@code [signzy, digitap]}) — the ordered
 * list of provider ids the {@code RoutingVerificationPort} tries per capability: it calls each in turn,
 * skipping a provider that does not offer the capability and falling through to the next on a failure,
 * returning the first success. Provider ids: {@code signzy}, {@code digitap}.
 */
@ConfigurationProperties(prefix = "navix.verification")
public record VerificationChainProperties(
        List<String> chain
) {

    /** The effective chain, defaulting to Signzy → Digitap when unset/blank. */
    public List<String> effectiveChain() {
        return (chain == null || chain.isEmpty()) ? List.of("signzy", "digitap") : chain;
    }
}
