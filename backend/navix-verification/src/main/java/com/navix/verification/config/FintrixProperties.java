package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.fintrix.*} configuration block — Fintrix is the PRIMARY bureau provider
 * ({@code POST /crif_combine}, CRIF Highmark). Auth scheme: HTTP Basic
 * {@code base64(clientId:clientSecret)}, same shape as Digitap.
 */
@ConfigurationProperties(prefix = "navix.fintrix")
public record FintrixProperties(
        String baseUrl,
        String clientId,
        String clientSecret
) {
}
