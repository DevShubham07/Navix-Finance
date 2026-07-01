package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when a referral qualifies (the referred borrower's first loan is disbursed) and the two
 * reward payouts are created. Drives the {@code REFERRAL_PAYOUT_PENDING} alert to the Disbursement
 * Heads so they action the new payouts. Carried inline (async listener has no transaction/context).
 */
public record ReferralPayoutCreatedEvent(
        Long referralId,
        Long referrerCustomerId,
        Long referredCustomerId,
        Long loanId,
        long amountPaise,
        Instant at) {
}
