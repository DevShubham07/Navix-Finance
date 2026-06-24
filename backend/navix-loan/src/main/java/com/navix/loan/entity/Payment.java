package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A repayment made against a {@link Loan}.
 *
 * <p>Single-repayment product, but partial payments are allowed: the loan stays
 * outstanding until the balance reaches zero, at which point it closes.
 * Prepayment is allowed anytime (interest only to the day of repayment, no
 * penalty). Manual repayments (UPI / bank transfer) must carry proof.
 *
 * TODO: wire to RepaymentService; recompute Loan.outstanding on each payment.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseAuditEntity {

    /** FK to the loan being repaid. */
    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    /** Amount paid in this transaction, in paise. */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** Method used to pay. */
    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private PaymentMethod method;

    /** Verification state (proof required before VERIFIED). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status;

    /** UPI / bank transaction reference supplied by the borrower. */
    @Column(name = "txn_ref")
    private String txnRef;

    /** Storage URL/key for an uploaded payment screenshot. */
    @Column(name = "proof_url")
    private String proofUrl;

    /** Date the payment was made. */
    @Column(name = "paid_on")
    private LocalDate paidOn;

    /** True if this is a partial payment (balance remains outstanding). */
    @Column(name = "partial", nullable = false)
    private boolean partial;
}
