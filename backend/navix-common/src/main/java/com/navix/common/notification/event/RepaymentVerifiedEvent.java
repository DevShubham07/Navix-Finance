package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when the accountant verifies a repayment. {@code closedTheLoan} lets the listener skip
 * {@code REPAYMENT_VERIFIED} on the final (closing) payment — {@code LOAN_CLOSED} (emitted by the
 * REPAID transition) already covers that case, avoiding a double notification.
 */
public record RepaymentVerifiedEvent(
        Long loanId,
        Long customerId,
        Long paymentId,
        long amountPaise,
        boolean closedTheLoan,
        Instant at) {
}
