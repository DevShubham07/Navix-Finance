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
import java.time.Instant;
import java.time.LocalDate;
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

    /** Customer / borrower reference (FK to IAM user). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Amount the customer requested, in paise. Null until the borrower applies. */
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

    /**
     * How far the borrower has walked the onboarding journey — the durable pointer that makes
     * resuming on a second device work (V44; revamp.md C1). Resume used to be a localStorage
     * breadcrumb, so a fresh browser restarted the wizard.
     *
     * <p>Advisory, not authoritative on its own: {@code JourneyService} takes the furthest of this
     * value and what the status + verification rows prove, so a stale pointer can never send a
     * borrower back through work they have already completed.
     */
    @Column(name = "journey_step", length = 40)
    private String journeyStep;

    // ---- the credit sanction (V45) -------------------------------------------------
    // Written once by the Credit Executive's final decision. The sanctioned amount is a CEILING:
    // the borrower may draw down less in Phase 3, so it is deliberately separate from
    // amountRequested (what they end up asking for).

    @Column(name = "sanctioned_amount_paise")
    private Long sanctionedAmountPaise;

    /** Repayment date the credit team set. Overrides the salary-derived due date at disbursal. */
    @Column(name = "approved_repayment_date")
    private LocalDate approvedRepaymentDate;

    @Column(name = "sanction_tenure_days")
    private Integer sanctionTenureDays;

    @Column(name = "sanction_remarks")
    private String sanctionRemarks;

    @Column(name = "sanctioned_by")
    private Long sanctionedBy;

    @Column(name = "sanctioned_at")
    private Instant sanctionedAt;

    /**
     * "Mark lead pending" — a tag, not a status (revamp.md decision 30). The application keeps its
     * place in the queue and the borrower is never notified; only staff see the reason.
     */
    @Column(name = "marked_pending_at")
    private Instant markedPendingAt;

    @Column(name = "pending_reason")
    private String pendingReason;

    // ---- the disbursal account (V46) ------------------------------------------------
    // Where THIS advance is paid. Deliberately separate from CustomerProfile.salaryAccount*, which
    // is where the borrower's salary lands and is what credit sanity-checked the file against —
    // overwriting that on a change would destroy the thing they verified.

    @Column(name = "disbursal_account_number", length = 32)
    private String disbursalAccountNumber;

    @Column(name = "disbursal_ifsc", length = 16)
    private String disbursalIfsc;

    @Column(name = "disbursal_holder_name", length = 160)
    private String disbursalHolderName;

    @Column(name = "disbursal_bank", length = 120)
    private String disbursalBank;

    /**
     * The borrower typed an account other than their salary account — the only case that fires a
     * penny drop (revamp.md decision 9). When false the money goes to an account number nobody
     * verified externally; that is the accepted trade-off, recorded here rather than inferred.
     */
    @Column(name = "disbursal_account_changed", nullable = false)
    private Boolean disbursalAccountChanged = Boolean.FALSE;

    @Column(name = "disbursal_account_verified", nullable = false)
    private Boolean disbursalAccountVerified = Boolean.FALSE;

    @Column(name = "disbursal_confirmed_at")
    private Instant disbursalConfirmedAt;

    // ---- re-apply provenance (V47) ---------------------------------------------------

    /**
     * The prior application this re-apply carried its sanction, verifications, references and
     * disbursal account over from (revamp.md decisions 45, 46). Null for a first-time borrower.
     *
     * <p>Load-bearing, not just documentation: {@code JourneyService} keys the shortened offer
     * journey on it, so a returning borrower isn't walked back through DigiLocker, references, the
     * selfie and the address check they already passed.
     */
    @Column(name = "reapplied_from")
    private Long reappliedFrom;
}
