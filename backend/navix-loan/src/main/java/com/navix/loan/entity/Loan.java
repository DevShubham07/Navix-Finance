package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.LoanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A disbursed (or in-flight) loan.
 *
 * <p>Single-repayment product: the borrower repays {@code totalRepayable}
 * on the due date (aligned to their salary credit). Money math is computed
 * by {@code LoanMath}.
 *
 * TODO: extend audit fields via {@link BaseAuditEntity} (createdBy/at, etc.)
 * and add maker-checker / disbursement linkage.
 */
@Entity
@Table(name = "loan")
@Getter
@Setter
@NoArgsConstructor
public class Loan extends BaseAuditEntity {

    /** Customer / borrower reference (FK to IAM user); the loan originates
     *  from that customer's approved {@code LoanApplication}. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Sanctioned principal, in paise. */
    @Column(name = "principal", nullable = false)
    private Long principal;

    /** Up-front processing fee (10% of principal), in paise. */
    @Column(name = "processing_fee")
    private Long processingFee;

    /** GST charged on the processing fee (18% of the fee), in paise. */
    @Column(name = "gst")
    private Long gst;

    /** Net amount actually credited to the borrower, in paise. */
    @Column(name = "net_disbursed")
    private Long netDisbursed;

    /** Daily interest rate (1%/day) — a rate, not an amount. */
    @Column(name = "daily_interest_rate", precision = 6, scale = 4)
    private BigDecimal dailyInterestRate;

    /** Date the loan was disbursed. */
    @Column(name = "disbursed_on")
    private LocalDate disbursedOn;

    /** Single-repayment due date (aligned to salary credit day). */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Total amount repayable on the due date (principal + interest), in paise. */
    @Column(name = "total_repayable")
    private Long totalRepayable;

    /** Current outstanding amount (scheduled-to-due less verified payments), in paise. */
    @Column(name = "outstanding")
    private Long outstanding;

    /** Current loan status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanStatus status;

    /** Bank/UPI transaction reference for the outgoing disbursal (captured at release). */
    @Column(name = "disbursal_txn_ref", length = 64)
    private String disbursalTxnRef;

    /**
     * Effective status on read: an ACTIVE loan past its due date reads as OVERDUE. This is computed,
     * not persisted (the stored column stays ACTIVE until a collection case flips it to
     * IN_COLLECTIONS) — collectible queries already include both ACTIVE and OVERDUE.
     */
    public LoanStatus effectiveStatus(LocalDate asOf) {
        if (status == LoanStatus.ACTIVE && dueDate != null && asOf != null && asOf.isAfter(dueDate)) {
            return LoanStatus.OVERDUE;
        }
        return status;
    }
}
