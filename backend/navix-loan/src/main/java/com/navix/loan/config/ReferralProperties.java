package com.navix.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code navix.referral.*} — the refer-a-friend reward program. Read at boot from SSM/env/yaml
 * (auto-registered via {@code @ConfigurationPropertiesScan("com.navix")} on the app), so a value
 * change needs an app restart/redeploy to take effect.
 *
 * <p>{@code enabled} gates the whole program (issuing a code, redeeming a code, and creating the
 * reward payouts at disbursement). {@code rewardPaise} is the per-person reward in integer paise
 * (₹200 = {@code 20000}); it is snapshotted onto each {@code referral_payout} row at creation, so a
 * later config change never mutates an already-created payout.
 */
@ConfigurationProperties(prefix = "navix.referral")
public record ReferralProperties(
        boolean enabled,
        long rewardPaise
) {

    /** The reward in whole rupees (for display copy). */
    public long rewardRupees() {
        return rewardPaise / 100;
    }
}
