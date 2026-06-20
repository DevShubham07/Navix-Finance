package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.fintrix.*} configuration block.
 *
 * <p>baseUrl  -> {@code FINTRIX_BASE_URL} (default https://admin.fintrix.tech/__api/api/v1/)
 * clientId   -> {@code FINTRIX_CLIENT_ID}
 * clientSecret -> {@code FINTRIX_CLIENT_SECRET}
 *
 * <p>Auth scheme: HTTP Basic base64(clientId:clientSecret).
 */
@ConfigurationProperties(prefix = "navix.fintrix")
public record FintrixProperties(
        String baseUrl,
        String clientId,
        String clientSecret
) {
}
