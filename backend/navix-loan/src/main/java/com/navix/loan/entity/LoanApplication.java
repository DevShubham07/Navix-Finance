package com.navix.loan.entity;

import com.navix.loan.domain.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The single application aggregate (dfd.md §8). Carries the borrower's loan request through the
 * canonical lifecycle ({@link ApplicationStatus}) from DRAFT/KYC through credit decisioning and
 * disbursement to ACTIVE. The disbursed {@link Loan} (money/ledger) is linked via {@link #loanId}.
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

    /** Amount the applicant requested, in paise. Null until the borrower applies. */
    @Column(name = "amount_requested")
    private Long amountRequested;

    /** Eligible limit at the time of application (from income-risk), in paise. */
    @Column(name = "eligible_limit")
    private Long eligibleLimit;

    /** Stated purpose of the loan (captured when the borrower applies). */
    @Column(name = "purpose")
    private String purpose;

    /** Borrower's salary-credit day-of-month (1–31) — drives the salary-linked due date. */
    @Column(name = "salary_credit_day")
    private Integer salaryCreditDay;

    /** Credit Executive the Credit Head assigned this application to. */
    @Column(name = "assigned_executive_id")
    private Long assignedExecutiveId;

    /** The disbursed loan once ACTIVE; null before disbursement. */
    @Column(name = "loan_id")
    private Long loanId;

    /** Current position in the canonical lifecycle. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApplicationStatus status;
}
