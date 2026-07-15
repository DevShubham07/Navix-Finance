package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.digitap.*} configuration block (the FALLBACK verification provider).
 *
 * <p>Digitap exposes TWO hosts, so two base URLs are configured:
 * <ul>
 *   <li>{@code svcBaseUrl} -> {@code DIGITAP_SVC_BASE_URL} (KYC validation, Employment, Email);
 *       default preprod {@code https://svcdemo.digitap.work} (prod {@code https://svc.digitap.ai}).</li>
 *   <li>{@code apiBaseUrl} -> {@code DIGITAP_API_BASE_URL} (Credit Analytics, Location, Face-Match, OCR);
 *       default preprod {@code https://apidemo.digitap.work} (prod {@code https://api.digitap.ai}).</li>
 * </ul>
 *
 * <p>Auth scheme (both hosts): HTTP Basic {@code base64(clientId:clientSecret)}. clientId/secret differ
 * between the UAT/preprod and production environments.
 */
@ConfigurationProperties(prefix = "navix.digitap")
public record DigitapProperties(
        String svcBaseUrl,
        String apiBaseUrl,
        String clientId,
        String clientSecret
) {
}
