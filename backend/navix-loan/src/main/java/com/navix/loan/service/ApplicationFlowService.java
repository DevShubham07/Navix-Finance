package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the canonical application lifecycle (dfd.md §8): one aggregate, one status, server-enforced
 * transitions with role checks, separation-of-duties (the Credit Executive who recommends ≠ the
 * Credit Head who approves, D3), and an append-only {@link ApplicationEvent} trail. Auto-routes the
 * system transitions (exec-approved→head-pending, head-approved→disbursement-pending, disbursed→
 * active). At DISBURSED→ACTIVE it mints the 30-day loan via {@link LoanService#disburse}.
 *
 * <p>Identity is the demo {@link CurrentActor} (role from the {@code X-Demo-Actor-Role} header);
 * at go-live it becomes the JWT principal. ADMIN may act in any role (oversight).
 */
@Service
@RequiredArgsConstructor
public class ApplicationFlowService {

    private final LoanApplicationRepository applicationRepository;
    private final ApplicationEventRepository eventRepository;
    private final EligibilityService eligibilityService;
    private final LoanService loanService;
    private final StaffDirectory staffDirectory;
    // For the returning-borrower (reborrow) path: prior loan history (delinquency check) and the
    // saved KYC profile (identity/salary reuse — no re-collection).
    private final LoanRepository loanRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicantProfileRepository profileRepository;
    private final LoanMath loanMath;

    /** Loan statuses that mean the borrower was (or is) delinquent — triggers reborrow review. */
    private static final Set<LoanStatus> DELINQUENT_LOAN_STATUSES =
            Set.of(LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);

    /** Staff roles permitted to cancel a pre-disbursement application (alongside the owning borrower). */
    private static final Set<String> CANCEL_STAFF_ROLES = Set.of(
            "KYC_APPROVER", "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT");

    // ---- creation & borrower steps -------------------------------------------------

    @Transactional
    public LoanApplication createDraft(Long applicantId) {
        LoanApplication app = new LoanApplication();
        app.setApplicantId(applicantId);
        app.setStatus(ApplicationStatus.DRAFT);
        LoanApplication saved = applicationRepository.save(app);
        logEvent(saved, null, ApplicationStatus.DRAFT, "CREATE", null);
        return saved;
    }

    /**
     * Returning-borrower reborrow (W?): a repeat borrower starts a new advance reusing their saved
     * KYC profile — no re-collection. The actor's id is the applicantId (the BFF injects it).
     *
     * <p>Routing by standing, computed from loan history (no stored flag):
     * <ul>
     *   <li>clean history → {@link ApplicationStatus#PRE_APPROVED} (skips KYC + credit; on apply it
     *       goes straight to the Disbursement Head);</li>
     *   <li>any past delinquency (a loan currently/ever overdue, in collections, or repaid late) →
     *       {@link ApplicationStatus#REVIEW_PENDING} for a KYC-approver re-review every time.</li>
     * </ul>
     *
     * <p>Blocked while the borrower still has a live application/loan, and rejected if there is no
     * prior application to borrow against (the caller then falls back to a fresh signup).
     * Deliberately does <b>not</b> create a new {@code applicant_profile} row — identity carries over
     * (V12 keeps pan/aadhaar/mobile globally unique) and profile reads fall back to the prior one.
     */
    @Transactional
    public LoanApplication reborrow() {
        requireRole("BORROWER");
        Long applicantId = Long.valueOf(ActorContext.get().id());

        boolean hasLive = applicationRepository.findByApplicantId(applicantId).stream()
                .anyMatch(a -> !a.getStatus().isTerminal());
        if (hasLive) {
            throw new BusinessException("ACTIVE_LOAN",
                    "Finish or repay your current advance before borrowing again");
        }

        ApplicantProfile prior = latestProfileForApplicant(applicantId)
                .orElseThrow(() -> new BusinessException("NO_PRIOR_LOAN",
                        "No previous application found to borrow against"));
        Long salaryPaise = prior.getMonthlySalaryPaise();
        Long eligibleLimit = salaryPaise != null ? loanMath.eligibleLimitPaise(salaryPaise) : null;

        LoanApplication app = createDraft(applicantId);
        app.setEligibleLimit(eligibleLimit);
        app.setSalaryCreditDay(latestSalaryCreditDay(applicantId));

        boolean needsReview = hasPastDelinquency(applicantId);
        transition(app, needsReview ? ApplicationStatus.REVIEW_PENDING : ApplicationStatus.PRE_APPROVED,
                "REBORROW",
                needsReview ? "Past delinquency — KYC review required" : "Pre-approved returning borrower");
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication submitKyc(Long appId) {
        requireRole("BORROWER");
        LoanApplication app = require(appId);
        transition(app, ApplicationStatus.KYC_PENDING, "SUBMIT_KYC", null);
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication decideKyc(Long appId, boolean approve, String notes) {
        requireRole("KYC_APPROVER");
        LoanApplication app = require(appId);
        if (approve) {
            transition(app, ApplicationStatus.KYC_APPROVED, "KYC_APPROVE", notes);
        } else {
            transition(app, ApplicationStatus.KYC_REJECTED, "KYC_REJECT", notes);
        }
        return applicationRepository.save(app);
    }

    /**
     * Reborrow review decision (KYC approver): clear a flagged returning borrower so they may proceed
     * ({@code REVIEW_PENDING → PRE_APPROVED}) or reject them. Mirrors {@link #decideKyc} — same role.
     */
    @Transactional
    public LoanApplication decideReview(Long appId, boolean approve, String notes) {
        requireRole("KYC_APPROVER");
        LoanApplication app = require(appId);
        if (approve) {
            transition(app, ApplicationStatus.PRE_APPROVED, "REVIEW_APPROVE", notes);
        } else {
            transition(app, ApplicationStatus.REJECTED, "REVIEW_REJECT", notes);
        }
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication apply(Long appId, long amountPaise, String purpose, Long eligibleLimitPaise,
                                 Integer salaryCreditDay) {
        requireRole("BORROWER");
        LoanApplication app = require(appId);
        ApplicationStatus st = app.getStatus();
        // A fresh borrower applies after KYC; a returning borrower applies once PRE_APPROVED.
        if (st != ApplicationStatus.KYC_APPROVED && st != ApplicationStatus.PRE_APPROVED) {
            throw new BusinessException("NOT_APPLICABLE", "Borrower can only apply after approval");
        }
        if (amountPaise < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "Requested amount is below the minimum of ₹1,000");
        }
        if (eligibleLimitPaise != null && !eligibilityService.isEligible(amountPaise, eligibleLimitPaise)) {
            throw new BusinessException("LIMIT_EXCEEDED", "Requested amount exceeds the eligible limit");
        }
        app.setAmountRequested(amountPaise);
        app.setPurpose(purpose);
        // Keep the reborrow-computed limit if the caller didn't supply one.
        app.setEligibleLimit(eligibleLimitPaise != null ? eligibleLimitPaise : app.getEligibleLimit());
        app.setSalaryCreditDay(salaryCreditDay);
        if (st == ApplicationStatus.PRE_APPROVED) {
            // Pre-approved returning borrower → straight to the Disbursement Head (skips credit).
            transition(app, ApplicationStatus.DISBURSEMENT_PENDING, "APPLY_FAST_TRACK",
                    "amountPaise=" + amountPaise);
        } else {
            // Stays KYC_APPROVED ("applied"); the Credit Head's queue picks up applied applications.
            logEvent(app, st, st, "APPLY", "amountPaise=" + amountPaise);
        }
        return applicationRepository.save(app);
    }

    // ---- credit decisioning (W2) ---------------------------------------------------

    @Transactional
    public LoanApplication assignExecutive(Long appId, Long executiveId) {
        requireRole("CREDIT_HEAD");
        LoanApplication app = require(appId);
        if (app.getAmountRequested() == null) {
            throw new BusinessException("NOT_APPLIED", "Application has not been submitted by the borrower yet");
        }
        // Activation gating (dfd.md §13.4): the assignee must be an ACTIVE Credit Executive.
        if (!staffDirectory.isActiveWithRole(executiveId, "CREDIT_EXECUTIVE")) {
            throw new BusinessException("INVALID_ASSIGNEE",
                    "The assignee must be an active Credit Executive");
        }
        app.setAssignedExecutiveId(executiveId);
        transition(app, ApplicationStatus.CREDIT_EXEC_PENDING, "ASSIGN", "executiveId=" + executiveId);
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication execDecision(Long appId, boolean approve, String notes) {
        requireRole("CREDIT_EXECUTIVE");
        LoanApplication app = require(appId);
        if (approve) {
            transition(app, ApplicationStatus.CREDIT_EXEC_APPROVED, "EXEC_APPROVE", notes);
            transition(app, ApplicationStatus.CREDIT_HEAD_PENDING, "AUTO_ROUTE", null);
        } else {
            transition(app, ApplicationStatus.REJECTED, "EXEC_REJECT", notes);
        }
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication headDecision(Long appId, boolean approve, Long approvedAmountPaise, String notes) {
        requireRole("CREDIT_HEAD");
        LoanApplication app = require(appId);
        if (approve) {
            // SoD (D3): the approving Head must not be the recommending Executive.
            String recommender = actorOf(appId, ApplicationStatus.CREDIT_EXEC_APPROVED);
            if (recommender != null && recommender.equals(ActorContext.get().id())) {
                throw new BusinessException("SOD_VIOLATION",
                        "The recommending Credit Executive cannot also give final approval");
            }
            if (approvedAmountPaise != null) {
                app.setAmountRequested(approvedAmountPaise);
            }
            transition(app, ApplicationStatus.CREDIT_HEAD_APPROVED, "HEAD_APPROVE", notes);
            transition(app, ApplicationStatus.DISBURSEMENT_PENDING, "AUTO_ROUTE", null);
        } else {
            transition(app, ApplicationStatus.REJECTED, "HEAD_REJECT", notes);
        }
        return applicationRepository.save(app);
    }

    // ---- disbursement & accountant validation (W3) ---------------------------------

    @Transactional
    public LoanApplication disbursementDecision(Long appId, boolean accept, String txnRef, String notes) {
        requireRole("DISBURSEMENT_HEAD");
        LoanApplication app = require(appId);
        if (!accept) {
            transition(app, ApplicationStatus.REJECTED, "DISB_REJECT", notes);
        } else if (txnRef != null && !txnRef.isBlank()) {
            // Fast path: a transaction id means the transfer is already done — release directly,
            // skipping the accountant gate (DISBURSEMENT_PENDING → DISBURSED → ACTIVE).
            finalizeDisbursal(app, txnRef, notes);
        } else {
            // No transaction id → hand off to the accountant to confirm the transfer.
            // (Sanction letter generation → S3 is a deferred document step.)
            transition(app, ApplicationStatus.ACCOUNTANT_PENDING, "DISB_ACCEPT", notes);
        }
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication accountantValidate(Long appId, boolean success, String txnRef, String notes) {
        requireRole("ACCOUNTANT");
        LoanApplication app = require(appId);
        if (success) {
            finalizeDisbursal(app, txnRef, notes);
        } else {
            transition(app, ApplicationStatus.DISBURSEMENT_FAILED, "VALIDATE_FAIL", notes);
        }
        return applicationRepository.save(app);
    }

    /**
     * Mint the loan and activate the application (DISBURSED → ACTIVE), recording the disbursal
     * transaction id. Shared by the Disbursement Head fast path and the accountant confirmation.
     */
    private void finalizeDisbursal(LoanApplication app, String txnRef, String notes) {
        transition(app, ApplicationStatus.DISBURSED, "VALIDATE_SUCCESS", notes);
        Loan loan = loanService.disburse(app, LocalDate.now(), txnRef);
        app.setLoanId(loan.getId());
        transition(app, ApplicationStatus.ACTIVE, "ACTIVATE", "loanId=" + loan.getId());
    }

    @Transactional
    public LoanApplication retryDisbursement(Long appId) {
        requireRole("DISBURSEMENT_HEAD");
        LoanApplication app = require(appId);
        transition(app, ApplicationStatus.ACCOUNTANT_PENDING, "RETRY", null);
        return applicationRepository.save(app);
    }

    @Transactional
    public LoanApplication cancel(Long appId, String notes) {
        LoanApplication app = require(appId);
        requireCancelAuthority(app);
        transition(app, ApplicationStatus.CANCELLED, "CANCEL", notes);
        return applicationRepository.save(app);
    }

    /**
     * Who may cancel: ADMIN (oversight); the owning BORROWER (their own application only); or a
     * pre-disbursement staff role. Anyone else (anonymous, an unrelated borrower) is rejected so a
     * cancel can't be driven by an actor with no authority over the application.
     */
    private void requireCancelAuthority(LoanApplication app) {
        CurrentActor actor = ActorContext.get();
        String role = actor.role();
        if ("ADMIN".equals(role)) {
            return;
        }
        if ("BORROWER".equals(role)) {
            if (!String.valueOf(app.getApplicantId()).equals(actor.id())) {
                throw new BusinessException("FORBIDDEN", "A borrower can only cancel their own application");
            }
            return;
        }
        if (CANCEL_STAFF_ROLES.contains(role)) {
            return;
        }
        throw new BusinessException("FORBIDDEN_ROLE", "You are not allowed to cancel this application");
    }

    /**
     * System close when the loan is fully repaid: mirrors the loan's closure onto the application
     * aggregate (the §5 invariant {@code ACTIVE → CLOSED} once Σ payments ≥ total). Called by
     * {@link RepaymentService} after the final verified payment zeroes the balance. Idempotent —
     * only an ACTIVE/OVERDUE application moves; anything else (no app for the loan, already closed)
     * is a no-op. Attributed to the current actor (the accountant verifying the payment).
     */
    @Transactional
    public void closeForLoan(Long loanId) {
        applicationRepository.findByLoanId(loanId).ifPresent(app -> {
            if (app.getStatus() == ApplicationStatus.ACTIVE || app.getStatus() == ApplicationStatus.OVERDUE) {
                transition(app, ApplicationStatus.CLOSED, "REPAID", "Loan fully repaid");
                applicationRepository.save(app);
            }
        });
    }

    // ---- reads ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LoanApplication get(Long appId) {
        return require(appId);
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> byStatus(ApplicationStatus status) {
        return applicationRepository.findByStatusOrderByIdAsc(status);
    }

    /** Credit Head queue: KYC-approved applications the borrower has actually applied on. */
    @Transactional(readOnly = true)
    public List<LoanApplication> creditHeadQueue() {
        return applicationRepository.findByStatusOrderByIdAsc(ApplicationStatus.KYC_APPROVED).stream()
                .filter(a -> a.getAmountRequested() != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ApplicationEvent> events(Long appId) {
        return eventRepository.findByApplicationIdOrderByAtAsc(appId);
    }

    // ---- internals -----------------------------------------------------------------

    private void transition(LoanApplication app, ApplicationStatus to, String action, String notes) {
        ApplicationStatus from = app.getStatus();
        if (!from.canTransitionTo(to)) {
            throw new BusinessException("ILLEGAL_TRANSITION", from + " → " + to + " is not allowed");
        }
        logEvent(app, from, to, action, notes);
        app.setStatus(to);
    }

    private void logEvent(LoanApplication app, ApplicationStatus from, ApplicationStatus to,
                          String action, String notes) {
        CurrentActor actor = ActorContext.get();
        ApplicationEvent event = new ApplicationEvent();
        event.setApplicationId(app.getId());
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setActorId(actor.id());
        event.setActorRole(actor.role());
        event.setAction(action);
        event.setNotes(notes);
        event.setAt(Instant.now());
        eventRepository.save(event);
    }

    /** Actor id who drove the transition INTO {@code status} (for SoD), or null. */
    private String actorOf(Long appId, ApplicationStatus status) {
        return eventRepository.findByApplicationIdOrderByAtAsc(appId).stream()
                .filter(e -> e.getToStatus() == status)
                .map(ApplicationEvent::getActorId)
                .reduce((first, second) -> second) // latest
                .orElse(null);
    }

    private void requireRole(String role) {
        CurrentActor actor = ActorContext.get();
        if (!role.equals(actor.role()) && !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role " + role);
        }
    }

    // ---- reborrow standing (computed from history; no stored flag) -----------------

    /**
     * Whether the applicant has ever been delinquent — a loan currently/ever overdue or in
     * collections, or one that was ultimately repaid <em>late</em> (now closed). Such a borrower is
     * re-reviewed by a KYC approver on every reborrow.
     */
    private boolean hasPastDelinquency(Long applicantId) {
        LocalDate today = LocalDate.now();
        for (Loan loan : loanRepository.findByApplicantId(applicantId)) {
            if (DELINQUENT_LOAN_STATUSES.contains(loan.effectiveStatus(today))) {
                return true;
            }
            if (loan.getDueDate() != null && paidLate(loan)) {
                return true;
            }
        }
        return false;
    }

    /** True if any verified repayment on the loan landed after its due date. */
    private boolean paidLate(Loan loan) {
        return paymentRepository.findByLoanId(loan.getId()).stream()
                .anyMatch(p -> p.getStatus() == PaymentStatus.VERIFIED
                        && p.getPaidOn() != null && p.getPaidOn().isAfter(loan.getDueDate()));
    }

    /** The applicant's most recent saved KYC profile (newest application first), if any. */
    private Optional<ApplicantProfile> latestProfileForApplicant(Long applicantId) {
        return applicationRepository.findByApplicantId(applicantId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> profileRepository.findByApplicationId(a.getId()).orElse(null))
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** The salary-credit day from the applicant's most recent application that captured one. */
    private Integer latestSalaryCreditDay(Long applicantId) {
        return applicationRepository.findByApplicantId(applicantId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(LoanApplication::getSalaryCreditDay)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private LoanApplication require(Long appId) {
        return applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
    }
}
