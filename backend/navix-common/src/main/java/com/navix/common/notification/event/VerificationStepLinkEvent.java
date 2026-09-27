package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when the credit team sends a borrower a "resume this step" link for a failed/abandoned
 * Phase-3 check (DigiLocker/Aadhaar, selfie, address, or the sanction-letter e-sign). The notification
 * engine maps this to {@code VERIFICATION_STEP_LINK} (to the borrower, IN_APP + EMAIL only).
 *
 * <p>Carries the step's own route rather than a rendered link, same reasoning as
 * {@link BureauQuestionPendingEvent}: the listener builds the actual
 * {@code /login?next=<route>} URL, so the base URL stays a deploy-time concern and never leaks into
 * the domain event.
 */
public record VerificationStepLinkEvent(
        Long customerId,
        Long applicationId,
        String checkType,
        String stepLabel,
        String route,
        Instant at) {
}
