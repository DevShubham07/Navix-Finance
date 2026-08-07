package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a collections officer records a payment a borrower made (V47).
 *
 * <p>{@code awaitingHead} decides who is nudged: a settlement waits on the Collection Head's
 * approval, everything else goes straight to the Accountant's validation queue (decision 43).
 */
public record CollectionPaymentRaisedEvent(
        UUID paymentId,
        UUID caseId,
        Long loanId,
        Long customerId,
        long amountPaise,
        String kind,
        boolean awaitingHead,
        Long raisedBy,
        Instant at) {
}
