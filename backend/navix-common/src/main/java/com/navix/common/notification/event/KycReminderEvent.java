package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when a staff member (KYC approver / admin) nudges a borrower to finish their pending
 * verification steps. The notification engine maps this to {@code KYC_REMINDER} (to the borrower,
 * across IN_APP/SMS/EMAIL). {@code pendingSteps} is a display-ready, comma-separated list of the
 * checks still outstanding (e.g. "Pan, Selfie, Penny drop").
 */
public record KycReminderEvent(
        Long customerId,
        Long applicationId,
        String pendingSteps,
        Instant at) {
}
