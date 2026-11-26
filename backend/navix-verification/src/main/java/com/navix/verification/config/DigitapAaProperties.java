package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code navix.digitap-aa.*} — Digitap Account Aggregator (bank-data). Auth = Basic base64(id:secret). */
@ConfigurationProperties(prefix = "navix.digitap-aa")
public record DigitapAaProperties(String baseUrl, String clientId, String clientSecret, String callbackUrl) {
}
