package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when the accountant rejects a recorded repayment (proof didn't match the transfer).
 * {@code reason} is one of the fixed picklist codes ({@code WRONG_REFERENCE}, {@code AMOUNT_MISMATCH},
 * {@code NOT_RECEIVED}, {@code UNREADABLE_PROOF}, {@code OTHER}); {@code note} is an optional free-text
 * elaboration. Both are surfaced on IN_APP/EMAIL only — the SMS template body is DLT-locked and stays
 * unchanged.
 */
public record RepaymentRejectedEvent(
        Long loanId,
        Long customerId,
        Long paymentId,
        long amountPaise,
        String reason,
        String note,
        Instant at) {
}
