package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.digilocker.*} configuration block.
 *
 * <p>baseUrl      -> DigiLocker API base
 * clientId      -> {@code DIGILOCKER_CLIENT_ID} (sent as header X-Client-ID)
 * clientSecret  -> {@code DIGILOCKER_CLIENT_SECRET} (sent as header X-Client-Secret)
 */
@ConfigurationProperties(prefix = "navix.digilocker")
public record DigiLockerProperties(
        String baseUrl,
        String clientId,
        String clientSecret
) {
}
