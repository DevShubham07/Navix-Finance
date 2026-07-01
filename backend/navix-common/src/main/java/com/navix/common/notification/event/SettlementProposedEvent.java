package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a collection officer proposes a settlement (→ the collection head approves, SoD). */
public record SettlementProposedEvent(
        UUID settlementId,
        UUID caseId,
        Long loanId,
        Long customerId,
        long amountPaise,
        Long proposedBy,
        Instant at) {
}
