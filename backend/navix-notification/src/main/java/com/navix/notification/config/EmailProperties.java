package com.navix.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email configuration ({@code navix.email.*}). {@code provider} selects the {@code EmailClient}
 * ({@code log} default, or {@code smtp}/{@code ses}); {@code enabled} gates delivery; {@code from} is
 * the sender address; {@code configurationSet} (SES only, optional) tags sends with a SES
 * configuration set so bounce/complaint events are emitted to SNS. Defaults are applied so the engine
 * works with no config present.
 */
@ConfigurationProperties(prefix = "navix.email")
public record EmailProperties(String provider, Boolean enabled, String from, String configurationSet) {

    public EmailProperties {
        if (provider == null || provider.isBlank()) {
            provider = "log";
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (from == null || from.isBlank()) {
            from = "NAVIX Finance <no-reply@navixfinance.example>";
        }
        if (configurationSet != null && configurationSet.isBlank()) {
            configurationSet = null;
        }
    }
}
