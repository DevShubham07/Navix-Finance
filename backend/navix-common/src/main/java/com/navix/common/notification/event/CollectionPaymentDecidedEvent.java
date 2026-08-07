package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the Accountant validates or rejects a collections payment (V47).
 *
 * <p>One event for both outcomes because they go to the same people for the same reason: the
 * officer who took the payment, and the Collection Head who owns the case, both need to know what
 * the checker decided and — when it's a rejection — {@code remarks} telling them why (decision 44).
 */
public record CollectionPaymentDecidedEvent(
        UUID paymentId,
        UUID caseId,
        Long loanId,
        Long customerId,
        long amountPaise,
        boolean validated,
        String remarks,
        Long raisedBy,
        Instant at) {
}
