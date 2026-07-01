package com.navix.common.notification.event;

import java.time.Instant;

/** Published when the accountant rejects a recorded repayment (proof didn't match the transfer). */
public record RepaymentRejectedEvent(
        Long loanId,
        Long customerId,
        Long paymentId,
        long amountPaise,
        Instant at) {
}
