package com.navix.notification.catalog;

/**
 * How a {@link NotificationType}'s audience is resolved into concrete recipients. A type's audience
 * is a {@code Set} of these, so one notification can fan out across a role (e.g. all ACTIVE credit
 * heads) <b>and</b> target multiple groups (borrower + disbursement heads) in a single declaration.
 * {@code TO_ADMINS} is defined but reserved/unused in v1 (ADMIN gets oversight via dashboards).
 */
public enum RecipientPolicy {
    TO_BORROWER,
    TO_ASSIGNED_EXECUTIVE,
    TO_KYC_APPROVERS,
    TO_CREDIT_HEADS,
    TO_CREDIT_EXECUTIVES,
    TO_DISBURSEMENT_HEADS,
    TO_ACCOUNTANTS,
    TO_COLLECTION_HEADS,
    TO_COLLECTION_EXECUTIVES,
    TO_ADMINS,
    TO_STAFF_SUBJECT
}
