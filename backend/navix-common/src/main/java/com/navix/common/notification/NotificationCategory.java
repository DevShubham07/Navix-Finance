package com.navix.common.notification;

/**
 * Coarse grouping of {@code NotificationType}s, used for labelling/filtering in the UI.
 * {@code SECURITY} is reserved for future login-alert / password-change notifications.
 */
public enum NotificationCategory {
    KYC,
    CREDIT,
    DISBURSEMENT,
    REPAYMENT,
    COLLECTIONS,
    STAFF_IAM,
    SECURITY,
    SYSTEM
}
