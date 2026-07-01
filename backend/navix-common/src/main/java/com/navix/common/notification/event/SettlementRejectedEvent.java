package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** Published when the Collection Head rejects a proposed settlement (→ notify the proposer). */
public record SettlementRejectedEvent(
        UUID settlementId,
        UUID caseId,
        Long loanId,
        Long customerId,
        long amountPaise,
        Long proposedBy,
        Instant at) {
}
