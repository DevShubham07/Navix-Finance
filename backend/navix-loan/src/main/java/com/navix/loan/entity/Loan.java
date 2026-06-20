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

    /** Sanctioned principal. */
    @Column(name = "principal", nullable = false, precision = 14, scale = 2)
    private BigDecimal principal;

    /** Up-front processing fee (10% of principal). */
    @Column(name = "processing_fee", precision = 14, scale = 2)
    private BigDecimal processingFee;

    /** GST charged on the processing fee (18% of the fee). */
    @Column(name = "gst", precision = 14, scale = 2)
    private BigDecimal gst;

    /** Net amount actually credited to the borrower. */
    @Column(name = "net_disbursed", precision = 14, scale = 2)
    private BigDecimal netDisbursed;

    /** Daily interest rate (1%/day). */
    @Column(name = "daily_interest_rate", precision = 6, scale = 4)
    private BigDecimal dailyInterestRate;

    /** Date the loan was disbursed. */
    @Column(name = "disbursed_on")
    private LocalDate disbursedOn;

    /** Single-repayment due date (aligned to salary credit day). */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Total amount repayable on the due date (principal + interest). */
    @Column(name = "total_repayable", precision = 14, scale = 2)
    private BigDecimal totalRepayable;

    /** Current outstanding amount. */
    @Column(name = "outstanding", precision = 14, scale = 2)
    private BigDecimal outstanding;

    /** Current loan status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanStatus status;
}
