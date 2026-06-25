package com.navix.iam.domain;

/**
 * Staff roles used for access control and maker-checker separation of duties.
 * TODO: map each role to its permitted actions / API scopes.
 */
public enum StaffRole {
    KYC_APPROVER,
    CREDIT_EXECUTIVE,
    CREDIT_HEAD,
    DISBURSEMENT_HEAD,
    ACCOUNTANT,
    COLLECTION_HEAD,
    COLLECTION_EXECUTIVE,
    ADMIN,
    /** Internal read-only operations role (health, logs, read-only DB) — dfd.md §6.3 / W7. */
    DEVELOPER
}
