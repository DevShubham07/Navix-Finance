package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when a referral reward payout is marked PAID by the Disbursement Head (transaction id
 * logged). Drives the {@code REFERRAL_REWARD_CREDITED} "₹X has been credited" notification to the
 * beneficiary. Carried inline — the async listener has no {@code ActorContext}/transaction.
 * {@code beneficiaryRole} is a {@code ReferralBeneficiaryRole} name (the enum lives in navix-loan,
 * which navix-common must not depend on — hence String).
 */
public record ReferralRewardCreditedEvent(
        Long payoutId,
        Long beneficiaryApplicantId,
        Long counterpartyApplicantId,
        String beneficiaryRole,
        long amountPaise,
        String txnRef,
        Instant at) {
}
