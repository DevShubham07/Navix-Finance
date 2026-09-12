package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when an ADMIN raises a customer's eligible limit — the maximum they may be advanced
 * (V69). Drives an IN_APP + EMAIL notice to the borrower reporting the new limit (no SMS — see
 * {@code NotificationType.LOAN_LIMIT_REVISED}).
 *
 * <p>Only an <b>increase</b> is published: a cleared or reduced limit is not something to push at a
 * borrower. {@code previousLimitPaise} is null when no override existed before, in which case the
 * limit was previously the 25%-of-salary figure. Plain record, all data inline — the async listener
 * has no {@code ActorContext} and no transaction.
 */
public record LoanLimitRevisedEvent(
        Long customerId,
        Long previousLimitPaise,
        long newLimitPaise,
        Instant at) {
}
