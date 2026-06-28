package com.navix.common.notification;

/**
 * Delivery channel for a notification. {@code IN_APP} is always attempted (the persisted row is the
 * inbox); {@code SMS}/{@code EMAIL} are address-gated per recipient at dispatch — a missing address
 * on an intended channel is recorded as a {@code SKIPPED} delivery rather than a failure.
 */
public enum NotificationChannel {
    IN_APP,
    SMS,
    EMAIL
}
