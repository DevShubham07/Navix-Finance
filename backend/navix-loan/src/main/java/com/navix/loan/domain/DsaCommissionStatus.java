package com.navix.loan.domain;

/**
 * Lifecycle of one DSA commission row: {@code ACCRUED} when the attributed loan disburses,
 * {@code PAYABLE} once that loan closes clean, {@code PAID} once ADMIN settles it with a
 * transaction id, or {@code VOID} when the loan ends in an approved settlement, default, or
 * write-off (the 3.5% pays only on a clean full repayment). Mirrors {@link ReferralPayoutStatus}'s
 * accrual-ledger shape, one step longer to carry the settlement/default void branch.
 */
public enum DsaCommissionStatus {
    ACCRUED,
    PAYABLE,
    PAID,
    VOID
}
