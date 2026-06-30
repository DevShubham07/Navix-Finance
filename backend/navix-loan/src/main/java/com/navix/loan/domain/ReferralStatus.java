package com.navix.loan.domain;

/**
 * Lifecycle of a captured referral relationship.
 *
 * <ul>
 *   <li>{@code PENDING} — a new borrower signed up with someone's code; no reward yet.</li>
 *   <li>{@code QUALIFIED} — the referred borrower's first loan was disbursed; the two ₹-reward
 *       payouts have been created.</li>
 *   <li>{@code REJECTED} — the referral was voided (reserved; not produced by the happy path).</li>
 * </ul>
 */
public enum ReferralStatus {
    PENDING,
    QUALIFIED,
    REJECTED
}
