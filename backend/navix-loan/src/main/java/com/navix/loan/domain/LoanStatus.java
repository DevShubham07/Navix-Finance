package com.navix.loan.domain;

/**
 * Lifecycle states of a loan, from application through closure.
 *
 * <p>Happy path:
 * APPLIED -> UNDER_REVIEW -> APPROVED -> DOCS_SIGNED -> BANK_VERIFIED
 * -> DISBURSING -> ACTIVE -> REPAID -> CLOSED.
 *
 * <p>Off-path: OVERDUE -> IN_COLLECTIONS, or DECLINED at review.
 */
public enum LoanStatus {
    APPLIED,
    UNDER_REVIEW,
    APPROVED,
    DOCS_SIGNED,
    BANK_VERIFIED,
    DISBURSING,
    ACTIVE,
    REPAID,
    OVERDUE,
    IN_COLLECTIONS,
    CLOSED,
    DECLINED
}
