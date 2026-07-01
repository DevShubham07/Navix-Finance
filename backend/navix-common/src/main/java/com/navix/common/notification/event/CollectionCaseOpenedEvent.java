package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a fresh collection case is opened on an overdue loan ({@code caseId} is a UUID PK). */
public record CollectionCaseOpenedEvent(
        UUID caseId,
        Long loanId,
        Long customerId,
        Instant at) {
}
