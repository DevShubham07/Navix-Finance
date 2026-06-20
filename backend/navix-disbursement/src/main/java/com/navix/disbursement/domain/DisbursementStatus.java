package com.navix.disbursement.domain;

/**
 * Lifecycle of a disbursement request as it moves through the maker-checker
 * approval chain and the manual bank transfer step.
 *
 * Flow: PENDING_CREDIT_REVIEW -> CREDIT_RECOMMENDED -> CREDIT_APPROVED
 *       -> RELEASE_AUTHORISED -> TRANSFER_PENDING -> TRANSFER_CONFIRMED / TRANSFER_FAILED.
 */
public enum DisbursementStatus {
    /** Awaiting Credit Executive review (maker). */
    PENDING_CREDIT_REVIEW,
    /** Credit Executive recommended; awaiting Credit Head. */
    CREDIT_RECOMMENDED,
    /** Credit Head approved; awaiting Disbursement Head release. */
    CREDIT_APPROVED,
    /** Disbursement Head authorised release; ready for transfer. */
    RELEASE_AUTHORISED,
    /** Bank transfer initiated; awaiting Accountant confirmation. */
    TRANSFER_PENDING,
    /** Accountant confirmed transfer success; loan can be activated. */
    TRANSFER_CONFIRMED,
    /** Accountant marked transfer as failed. */
    TRANSFER_FAILED
}
