package com.navix.notification.catalog;

/**
 * How a {@link NotificationType}'s audience is resolved into concrete recipients. A type's audience
 * is a {@code Set} of these, so one notification can fan out across a role (e.g. all ACTIVE credit
 * heads) <b>and</b> target multiple groups (borrower + disbursement heads) in a single declaration.
 * {@code TO_ADMINS} resolves to every ACTIVE staff user with role ADMIN.
 */
public enum RecipientPolicy {
    TO_BORROWER,
    TO_ASSIGNED_EXECUTIVE,
    /**
     * The credit team — Credit Heads + Credit Executives. Replaced {@code TO_KYC_APPROVERS} when the
     * dedicated KYC_APPROVER role was deleted (V45) and the credit roles absorbed its work.
     */
    TO_CREDIT_TEAM,
    TO_CREDIT_HEADS,
    TO_CREDIT_EXECUTIVES,
    TO_DISBURSEMENT_HEADS,
    TO_ACCOUNTANTS,
    TO_COLLECTION_HEADS,
    TO_COLLECTION_EXECUTIVES,
    TO_ADMINS,
    TO_STAFF_SUBJECT
}
