package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the {@code MandatePort} seam (Digio NACH eMandate).
 *
 * @param provider          {@code digio} (default) or {@code mock}; the mock is reachable only by naming it
 * @param baseUrl           Digio API host, e.g. {@code https://api.digio.in}
 * @param clientId          HTTP Basic user
 * @param clientSecret      HTTP Basic password
 * @param webhookSecret     verifies Digio's callbacks; unused until the webhook lands but required now so the
 *                          deploy that adds the webhook cannot silently run without it
 * @param corporateConfigId our NPCI utility-code configuration at Digio; every create is filed under it
 * @param narration         free-text label for the mandate (reserved, not yet sent)
 * @param expireInDays      how long the borrower has to authorise (Digio allows 1 to 90)
 */
@ConfigurationProperties(prefix = "navix.digio")
public record DigioProperties(
        String provider,
        String baseUrl,
        String clientId,
        String clientSecret,
        String webhookSecret,
        String corporateConfigId,
        String narration,
        Integer expireInDays
) {
}
