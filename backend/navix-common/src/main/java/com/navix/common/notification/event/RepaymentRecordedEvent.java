package com.navix.common.notification.event;

import java.time.Instant;

/** Published when a borrower records a manual repayment (→ PENDING_VERIFICATION). */
public record RepaymentRecordedEvent(
        Long loanId,
        Long applicantId,
        Long paymentId,
        long amountPaise,
        Instant at) {
}
