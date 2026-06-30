package com.navix.collections.entity;

/**
 * Maker-checker state of a {@link Settlement}: an officer PROPOSES it, then the Collection Head
 * either APPROVES (it becomes the operative full-and-final figure) or REJECTS it.
 */
public enum SettlementStatus {
    PROPOSED,
    APPROVED,
    REJECTED
}
