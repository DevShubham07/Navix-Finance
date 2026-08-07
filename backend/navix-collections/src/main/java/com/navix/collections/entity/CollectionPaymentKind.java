package com.navix.collections.entity;

/**
 * What a collections officer is recording (revamp.md decision 43). The kind decides the route, not
 * the arithmetic — every kind ends at the Accountant, but a settlement stops at the Collection Head
 * first because it writes off part of the debt.
 */
public enum CollectionPaymentKind {

    /** The borrower paid something toward the balance; the rest is still owed. */
    PART_PAYMENT,

    /** The borrower cleared the balance in full. */
    FULL_PAYMENT,

    /**
     * A negotiated full-and-final for less than the balance. Requires an approved
     * {@link Settlement} on the case — the Collection Head's approval is what makes the concession,
     * and this payment is only its settlement.
     */
    SETTLEMENT;

    /** Whether this kind needs the Collection Head's approval before reaching the Accountant. */
    public boolean needsHeadApproval() {
        return this == SETTLEMENT;
    }
}
