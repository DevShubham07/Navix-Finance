package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when an ADMIN corrects the sanctioned amount on an application still awaiting
 * disbursement. Drives an IN_APP + EMAIL notice to the borrower reporting the revised figure
 * (no SMS — see {@code NotificationType.SANCTIONED_AMOUNT_REVISED}). Plain record, all data
 * inline — the async listener has no {@code ActorContext} and no transaction.
 */
public record SanctionedAmountRevisedEvent(
        Long customerId,
        Long applicationId,
        long previousAmountPaise,
        long newAmountPaise,
        Instant at) {
}
