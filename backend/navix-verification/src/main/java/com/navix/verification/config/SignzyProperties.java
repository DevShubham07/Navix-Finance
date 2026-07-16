package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code navix.signzy.*} configuration block (the PRIMARY verification provider).
 *
 * <p>baseUrl -> {@code SIGNZY_BASE_URL} (default the PREPRODUCTION host
 * {@code https://api-preproduction.signzy.app}; switch to {@code https://api.signzy.app} for prod).
 * token -> {@code SIGNZY_TOKEN} — Signzy's opaque bearer-style token sent RAW in the
 * {@code Authorization} header (NOT {@code Basic}/{@code Bearer}). Issued by Signzy support/CSM.
 * clientUniqueId -> {@code SIGNZY_CLIENT_UNIQUE_ID} — the account's unique id, sent as the
 * {@code x-client-unique-id} header (Signzy requires it alongside the token).
 */
@ConfigurationProperties(prefix = "navix.signzy")
public record SignzyProperties(
        String baseUrl,
        String token,
        String clientUniqueId,
        // Signzy PRODUCTION account (api.signzy.app) used for the capabilities enabled there — PAN 206AB
        // (with the unmasked name) and reverse-geocoding for address. The preprod account above keeps
        // serving penny-drop / bureau / DigiLocker (which are not entitled on the prod account).
        String prodBaseUrl,
        String prodToken
) {
}
