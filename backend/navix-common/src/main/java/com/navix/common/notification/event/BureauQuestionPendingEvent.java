package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when a borrower needs to answer the credit bureau's knowledge-based-authentication (KBA)
 * question before their credit report can be released. The notification engine maps this to
 * {@code BUREAU_QUESTION_PENDING} (to the borrower, IN_APP + EMAIL only).
 *
 * <p>Carries no question text on purpose. Every pull mints a NEW question and invalidates the old
 * one, so a question copied into an email would be stale by the time it was read - the message links
 * the borrower to the live screen instead.
 */
public record BureauQuestionPendingEvent(
        Long customerId,
        Long applicationId,
        Instant at) {
}
