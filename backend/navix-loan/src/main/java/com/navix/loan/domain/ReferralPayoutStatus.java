package com.navix.loan.domain;

/**
 * State of a single ₹-reward payout: {@code PENDING} until the Disbursement Head pays it manually and
 * logs a transaction id, then {@code PAID}.
 */
public enum ReferralPayoutStatus {
    PENDING,
    PAID
}
