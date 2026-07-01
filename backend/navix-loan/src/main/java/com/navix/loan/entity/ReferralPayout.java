package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.ReferralBeneficiaryRole;
import com.navix.loan.domain.ReferralPayoutStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One ₹-reward owed to one beneficiary (two are created per qualified referral — one for the referrer,
 * one for the referred). {@code PENDING} until the Disbursement Head pays it manually and logs a
 * transaction id, then {@code PAID}. This table backs both the payout approval dashboard and the
 * separate referral-expense view. {@code amountPaise} is snapshotted at creation from
 * {@code navix.referral.reward-paise}.
 */
@Entity
@Table(name = "referral_payout")
@Getter
@Setter
@NoArgsConstructor
public class ReferralPayout extends BaseAuditEntity {

    /** The {@link Referral} this payout settles. */
    @Column(name = "referral_id", nullable = false)
    private Long referralId;

    /** Who is paid. */
    @Column(name = "beneficiary_customer_id", nullable = false)
    private Long beneficiaryCustomerId;

    /** Whether the beneficiary is the referrer or the referred borrower. */
    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_role", nullable = false, length = 16)
    private ReferralBeneficiaryRole beneficiaryRole;

    /** The other party in the referral (for context/display); may be null. */
    @Column(name = "counterparty_customer_id")
    private Long counterpartyCustomerId;

    /** Reward amount in integer paise (snapshot at creation). */
    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    /** Payout state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReferralPayoutStatus status = ReferralPayoutStatus.PENDING;

    /** Bank/UPI transaction reference logged by the Disbursement Head when paid. */
    @Column(name = "txn_ref", length = 64)
    private String txnRef;

    /** When the payout was marked paid, or null. */
    @Column(name = "paid_at")
    private Instant paidAt;

    /** The Disbursement Head / admin who paid it (their name), or null. */
    @Column(name = "paid_by", length = 255)
    private String paidBy;

    /** The referred borrower's loan whose disbursement triggered this reward. */
    @Column(name = "qualifying_loan_id")
    private Long qualifyingLoanId;
}
