package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a collection head approves a settlement (→ borrower full-and-final to pay). */
public record SettlementApprovedEvent(
        UUID settlementId,
        UUID caseId,
        Long loanId,
        Long applicantId,
        long amountPaise,
        Long approvedBy,
        Instant at) {
}
