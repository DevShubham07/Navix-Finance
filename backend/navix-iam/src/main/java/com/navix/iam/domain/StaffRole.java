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
    COLLECTIONS_HEAD,
    COLLECTION_OFFICER,
    ADMIN
}
