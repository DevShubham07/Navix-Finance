package com.navix.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the {@code EsignPort} seam. The provider credentials are NOT here — Signzy Contract eSign
 * reuses the production Signzy account already configured under {@code navix.signzy.prod-*}.
 *
 * @param provider           which adapter to wire: {@code signzy} (default) or {@code mock}. The mock is
 *                           reachable only by naming it explicitly, so it cannot be reached by accident
 *                           in a deployed environment; the demo seed script and unit tests set it.
 * @param callbackUrl        Signzy requires a callback URL on every contract. Ours points at
 *                           {@code /api/webhooks/signzy/contract} and only accelerates the poll — the
 *                           poll remains the source of truth. Blank disables the accelerator.
 * @param callbackSecret     shared secret echoed back by Signzy in the callback's Authorization header.
 * @param nameMatchThreshold how closely the Aadhaar name must match the borrower's name, "0.01".."1.00".
 * @param logoUrl            brand mark shown on eMudhra's pages; blank leaves them unbranded.
 * @param headerColour       brand colour for eMudhra's page header.
 * @param buttonColour       brand colour for eMudhra's page buttons.
 * @param executerName       the legal entity initiating the signing, shown to the signer.
 */
@ConfigurationProperties(prefix = "navix.esign")
public record EsignProperties(
        String provider,
        String callbackUrl,
        String callbackSecret,
        String nameMatchThreshold,
        String logoUrl,
        String headerColour,
        String buttonColour,
        String executerName
) {
}
