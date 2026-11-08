package com.navix.loan.payu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayU Hosted Checkout settings ({@code navix.payu.*}).
 *
 * @param key           merchant key (sent in requests, not a secret by itself)
 * @param salt          merchant salt: SECRET, signs and checks every hash; never logged, never sent
 * @param checkoutUrl   where the browser posts the checkout form ({@code _payment})
 * @param commandUrl    the {@code verify_payment} endpoint
 * @param returnUrl     surl/furl/curl: where PayU posts the browser back
 * @param cardFeeBps    PLACEHOLDER per-mode card fee, {@code MODE:bps} comma-separated; blank = no card fee
 * @param cardFeeGstBps GST on that fee, in basis points (1800 = 18%)
 * @param allowSandbox  whether test.payu.in may be used. Off in production: a sandbox "payment" verifies as
 *                      captured with no money moved, so it would clear a real loan
 */
@ConfigurationProperties(prefix = "navix.payu")
public record PayuProperties(
        String key,
        String salt,
        String checkoutUrl,
        String commandUrl,
        String returnUrl,
        String cardFeeBps,
        int cardFeeGstBps,
        boolean allowSandbox
) {
    public boolean credentialsSet() {
        return key != null && !key.isBlank() && salt != null && !salt.isBlank();
    }

    /** Checked on every call: the feature flag flips with no restart, so a boot-time guard alone is not enough. */
    public boolean hostsAllowed() {
        return allowSandbox || !(isSandbox(checkoutUrl) || isSandbox(commandUrl));
    }

    private static boolean isSandbox(String url) {
        return url != null && url.contains("test.payu.in");
    }
}
