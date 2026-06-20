package com.navix.loan.entity;

import com.navix.loan.domain.LoanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An application for a loan, before a {@link Loan} is created on approval.
 *
 * TODO: link to applicant/IAM and to the originating risk assessment.
 */
@Entity
@Table(name = "loan_application")
@Getter
@Setter
@NoArgsConstructor
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Applicant / borrower reference (FK to IAM user). */
    @Column(name = "applicant_id", nullable = false)
    private Long applicantId;

    /** Amount the applicant requested. */
    @Column(name = "amount_requested", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountRequested;

    /** Eligible limit at the time of application (from income-risk). */
    @Column(name = "eligible_limit", precision = 14, scale = 2)
    private BigDecimal eligibleLimit;

    /** Current application status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanStatus status;
}
