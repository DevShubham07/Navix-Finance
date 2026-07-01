package com.navix.common.notification.event;

/**
 * Published by the daily payment-reminder scheduler for each live loan that needs a nudge.
 *
 * <p>Unlike the other notification events this one is <b>time-driven</b> (no business transaction to
 * wait on), so its listener is a plain {@code @Async @EventListener} rather than an
 * {@code @TransactionalEventListener(AFTER_COMMIT)}. {@code days} is the days-to-due when
 * {@code overdue} is false (the penalty-free countdown), and the days-overdue when it is true.
 */
public record PaymentReminderEvent(
        Long loanId,
        Long customerId,
        boolean overdue,
        long outstandingPaise,
        int days) {
}
