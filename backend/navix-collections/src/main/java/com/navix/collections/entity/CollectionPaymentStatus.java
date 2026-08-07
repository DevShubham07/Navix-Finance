package com.navix.collections.entity;

/**
 * Where a collections payment sits in its approval chain (V47).
 *
 * <p>{@code PENDING_HEAD → PENDING_ACCOUNTANT → VALIDATED} for a settlement; part and full payments
 * start at {@code PENDING_ACCOUNTANT} — they concede nothing, so there is nothing for the Collection
 * Head to approve. {@code REJECTED} is terminal from either pending state.
 */
public enum CollectionPaymentStatus {
    PENDING_HEAD,
    PENDING_ACCOUNTANT,
    VALIDATED,
    REJECTED;

    public boolean isPending() {
        return this == PENDING_HEAD || this == PENDING_ACCOUNTANT;
    }
}
