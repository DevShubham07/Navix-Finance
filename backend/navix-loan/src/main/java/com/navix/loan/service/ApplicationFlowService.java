package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

    private static final Logger log = LoggerFactory.getLogger(ApplicationFlowService.class);

    private final LoanApplicationRepository applicationRepository;
    private final ApplicationEventRepository eventRepository;
    private final EligibilityService eligibilityService;
    private final LoanService loanService;
    private final StaffDirectory staffDirectory;
    // For the returning-borrower (reborrow) path: prior loan history (delinquency check) and the
    // saved KYC profile (identity/salary reuse — no re-collection).
    private final LoanRepository loanRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerProfileRepository profileRepository;
    private final LoanMath loanMath;
    private final ApplicationEventPublisher eventPublisher;
    // Refer-a-friend: at the referred borrower's first disbursal this grants both parties their reward
    // (in-band, atomic with the loan mint). A no-op when the program is off or there's no referral.
    private final ReferralService referralService;

    /** Loan statuses that mean the borrower was (or is) delinquent — triggers reborrow review. */
    private static final Set<LoanStatus> DELINQUENT_LOAN_STATUSES =
            Set.of(LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);

    /**
     * Application statuses that represent an already-disbursed, still-live loan. One advance at a time:
     * a returning borrower holding a live loan is <b>blocked</b> from starting a new application — they
     * must fully repay first.
     */
    private static final Set<ApplicationStatus> LIVE_LOAN_STATUSES =
            Set.of(ApplicationStatus.ACTIVE, ApplicationStatus.OVERDUE, ApplicationStatus.DEFAULTED);

    /** Staff roles permitted to cancel a pre-disbursement application (alongside the owning borrower). */
    private static final Set<String> CANCEL_STAFF_ROLES = Set.of(
            "KYC_APPROVER", "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT");

    // ---- creation & borrower steps -------------------------------------------------

    @Transactional
    public LoanApplication createDraft(Long customerId) {
        assertCanStartNewApplication(customerId);
        LoanApplication app = new LoanApplication();
        app.setCustomerId(customerId);
        app.setStatus(ApplicationStatus.DRAFT);
        LoanApplication saved = applicationRepository.save(app);
        logEvent(saved, null, ApplicationStatus.DRAFT, "CREATE", null);
        return saved;
    }

    /**
     * Returning-borrower reborrow (W?): a repeat borrower starts a new advance reusing their saved
     * KYC profile — no re-collection. The actor's id is the customerId (the BFF injects it).
     *
     * <p>One advance at a time: a borrower holding a live loan (ACTIVE/OVERDUE/DEFAULTED) — or with a
     * pre-loan application still moving through the pipeline — is <b>blocked</b> and must fully repay /
     * finish first ({@link #assertCanStartNewApplication}). Rejected if there is no prior application to
     * borrow against (the caller then falls back to a fresh signup).
     *
     * <p>Routing by standing, computed from loan history (no stored flag) — <b>past delinquency is the
     * only trigger</b>:
     * <ul>
     *   <li>ever overdue / repaid late → {@link ApplicationStatus#REVIEW_PENDING}: a KYC approver must
     *       clear them ({@code REVIEW_PENDING → PRE_APPROVED}) before they can proceed;</li>
     *   <li>clean history → {@link ApplicationStatus#PRE_APPROVED} (skips KYC + credit; on apply it goes
     *       straight to the Disbursement Head). Credit score does <b>not</b> gate reborrow.</li>
     * </ul>
     *
     * <p>The carried-over KYC is cloned into a fresh {@code customer_profile} row for the new
     * application ({@link #copyProfileForReborrow}) so both the re-review and the disbursement review see
     * the full picture; the salary day is reused from the prior application (never re-collected).
     */
    @Transactional
    public LoanApplication reborrow() {
        requireRole("BORROWER");
        Long customerId = Long.valueOf(ActorContext.get().id());

        // One advance at a time: a live loan or an in-flight application blocks a fresh reborrow
        // (checked before the prior-profile lookup so ACTIVE_LOAN takes precedence).
        assertCanStartNewApplication(customerId);

        CustomerProfile prior = latestProfileForCustomer(customerId)
                .orElseThrow(() -> new BusinessException("NO_PRIOR_LOAN",
                        "No previous application found to borrow against"));
        Long salaryPaise = prior.getMonthlySalaryPaise();
        Long eligibleLimit = salaryPaise != null ? loanMath.eligibleLimitPaise(salaryPaise) : null;

        LoanApplication app = createDraft(customerId);
        app.setEligibleLimit(eligibleLimit);
        app.setSalaryCreditDay(latestSalaryCreditDay(customerId)); // reuse the borrower's original salary day

        // Clone the carried-over KYC into a profile row of THIS application's own — needed for both the
        // fast-track disbursement review and the KYC re-review (no onboarding wizard runs on either path).
        copyProfileForReborrow(prior, app.getId());

        // Past delinquency is the ONLY trigger for re-review: a borrower who was ever overdue (or repaid
        // late) must be cleared by a KYC approver; everyone else is pre-approved straight through. Credit
        // score does not gate reborrow.
        // Action is "REBORROW" for both forks — the notification listener (NotificationEventListener
        // .mapAction) keys on the action + toStatus to pick REBORROW_REVIEW_PENDING vs REBORROW_PREAPPROVED.
        if (hasPastDelinquency(customerId)) {
            transition(app, ApplicationStatus.REVIEW_PENDING, "REBORROW",
                    "Past delinquency - KYC re-review required");
        } else {
            // Clean history → pre-approved; choosing an amount goes straight to the Disbursement Head.
            transition(app, ApplicationStatus.PRE_APPROVED, "REBORROW", "Pre-approved returning borrower");
        }
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
        // Keep the reborrow-carried salary day if the caller didn't supply one (a reborrow reuses the
        // borrower's original day and never re-asks); a fresh borrower always sends the picked value.
        app.setSalaryCreditDay(salaryCreditDay != null ? salaryCreditDay : app.getSalaryCreditDay());
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
        // ADMIN is exempt — oversight may self-assign and drive the credit step solo (per-step control).
        if (!"ADMIN".equals(ActorContext.get().role())
                && !staffDirectory.isActiveWithRole(executiveId, "CREDIT_EXECUTIVE")) {
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
            // ADMIN is exempt — oversight may approve its own recommendation (per-step control).
            if (!"ADMIN".equals(ActorContext.get().role())) {
                String recommender = actorOf(appId, ApplicationStatus.CREDIT_EXEC_APPROVED);
                if (recommender != null && recommender.equals(ActorContext.get().id())) {
                    log.warn("SoD violation blocked app={} actor={} cannot approve own recommendation",
                            appId, ActorContext.get().id());
                    throw new BusinessException("SOD_VIOLATION",
                            "The recommending Credit Executive cannot also give final approval");
                }
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

    /**
     * KYC-approver credit fast-path (instant-loan model). A KYC approver clears the credit gate on an
     * <em>applied</em> {@code KYC_APPROVED} application in one step: approve routes straight to
     * {@code DISBURSEMENT_PENDING} (the Disbursement Head still releases the funds), reject → {@code REJECTED}.
     * This deliberately collapses the credit exec→head maker-checker for KYC approvers — loans are now a
     * flat instant limit, so a separate credit underwriting pass isn't required. ADMIN may use it too.
     */
    @Transactional
    public LoanApplication kycCreditDecision(Long appId, boolean approve, Long approvedAmountPaise, String notes) {
        requireRole("KYC_APPROVER");
        LoanApplication app = require(appId);
        if (app.getStatus() != ApplicationStatus.KYC_APPROVED) {
            throw new BusinessException("NOT_APPLICABLE",
                    "Only a KYC-approved, applied application can be credit-approved here");
        }
        if (app.getAmountRequested() == null) {
            throw new BusinessException("NOT_APPLIED", "The borrower has not chosen an amount yet");
        }
        if (approve) {
            if (approvedAmountPaise != null) {
                app.setAmountRequested(approvedAmountPaise);
            }
            transition(app, ApplicationStatus.DISBURSEMENT_PENDING, "KYC_CREDIT_APPROVE", notes);
        } else {
            transition(app, ApplicationStatus.REJECTED, "KYC_CREDIT_REJECT", notes);
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
        // Refer-a-friend reward: if this borrower was referred and this is their first disbursal, grant
        // both parties their ₹reward (creates the pending payouts) — atomic with the loan mint.
        referralService.onLoanDisbursed(app.getCustomerId(), loan.getId());
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
            if (!String.valueOf(app.getCustomerId()).equals(actor.id())) {
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

    /** The calling borrower's own applications, newest first (for the "my loans/transactions" views). */
    @Transactional(readOnly = true)
    public List<LoanApplication> myApplications() {
        requireRole("BORROWER");
        Long customerId = Long.valueOf(ActorContext.get().id());
        return applicationRepository.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
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
            log.warn("illegal transition blocked app={} {} -> {} action={}", app.getId(), from, to, action);
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
        // Mirror the lifecycle event (already persisted to the DB audit table) into the log stream so
        // the state machine is debuggable in CloudWatch — ids + status enums + actor only, no PII.
        log.info("application {} {} -> {} action={} actor={}/{}",
                app.getId(), from, to, action, actor.id(), actor.role());
        // Fan out a domain event for the notification engine (consumed AFTER_COMMIT + async). All
        // data is carried inline — the async listener has no ActorContext/transaction. This single
        // publish covers every transition (incl. same-status APPLY → LOAN_APPLIED).
        eventPublisher.publishEvent(new ApplicationTransitionedEvent(
                app.getId(), app.getCustomerId(), app.getLoanId(),
                from != null ? from.name() : null, to != null ? to.name() : null,
                action, app.getAssignedExecutiveId(), actor.id(), actor.role(), event.getAt()));
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
     * One advance at a time: a borrower may not start a new application while they hold a live loan
     * (they must fully repay it first) or while a previous pre-disbursement application is still in
     * flight. Server-enforced so a direct create call can't bypass the UI gating.
     */
    private void assertCanStartNewApplication(Long customerId) {
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        if (apps.stream().anyMatch(a -> LIVE_LOAN_STATUSES.contains(a.getStatus()))) {
            throw new BusinessException("ACTIVE_LOAN", "Repay your current advance before borrowing again");
        }
        if (apps.stream().anyMatch(a -> !a.getStatus().isTerminal() && !LIVE_LOAN_STATUSES.contains(a.getStatus()))) {
            throw new BusinessException("ACTIVE_APPLICATION", "Finish your in-progress application before starting a new one");
        }
    }

    /**
     * Whether the customer has ever been delinquent — a loan currently/ever overdue or in
     * collections, or one that was ultimately repaid <em>late</em> (now closed). Such a borrower is
     * re-reviewed by a KYC approver on every reborrow.
     */
    private boolean hasPastDelinquency(Long customerId) {
        LocalDate today = LocalDate.now();
        for (Loan loan : loanRepository.findByCustomerId(customerId)) {
            if (DELINQUENT_LOAN_STATUSES.contains(loan.effectiveStatus(today))) {
                return true;
            }
            if (loan.getDueDate() != null && paidLate(loan)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clone a prior KYC profile into a fresh {@code customer_profile} row keyed to the new (reborrow)
     * application. Carries over identity, employment, salary, the credit brief AND the prior penny-drop
     * verification (the bank account is unchanged and the reborrow flow no longer re-runs penny-drop) so
     * the borrower needn't re-enter or re-verify anything and staff see the full picture. No-op if a
     * profile already exists for the new application (defensive; a fresh draft never has one).
     */
    private void copyProfileForReborrow(CustomerProfile prior, Long newAppId) {
        if (profileRepository.findByApplicationId(newAppId).isPresent()) {
            return;
        }
        CustomerProfile copy = new CustomerProfile();
        copy.setApplicationId(newAppId);
        copy.setFullName(prior.getFullName());
        copy.setPan(prior.getPan());
        copy.setMobile(prior.getMobile());
        copy.setDob(prior.getDob());
        copy.setAddress(prior.getAddress());
        copy.setEmployer(prior.getEmployer());
        copy.setEmploymentStatus(prior.getEmploymentStatus());
        copy.setMonthlySalaryPaise(prior.getMonthlySalaryPaise());
        copy.setSalaryBank(prior.getSalaryBank());
        copy.setEmail(prior.getEmail());
        copy.setBureauScore(prior.getBureauScore());
        copy.setBureauSource(prior.getBureauSource());
        copy.setRiskCategory(prior.getRiskCategory());
        copy.setPanVerified(prior.getPanVerified());
        copy.setAadhaarLinked(prior.getAadhaarLinked());
        copy.setEmailVerified(prior.getEmailVerified());
        copy.setAddressVerified(prior.getAddressVerified());
        copy.setPennyDropVerified(prior.getPennyDropVerified());
        copy.setNameMatchScore(prior.getNameMatchScore());
        copy.setDigilockerClientId(prior.getDigilockerClientId());
        copy.setAgreementAccepted(prior.getAgreementAccepted());
        copy.setCreditStarRating(prior.getCreditStarRating());
        copy.setCreditRecommendation(prior.getCreditRecommendation());
        copy.setCreditBriefSummary(prior.getCreditBriefSummary());
        copy.setCreditBriefGeneratedAt(prior.getCreditBriefGeneratedAt());
        copy.setCreditBriefFacts(prior.getCreditBriefFacts());
        profileRepository.save(copy);
    }

    /** True if any verified repayment on the loan landed after its due date. */
    private boolean paidLate(Loan loan) {
        return paymentRepository.findByLoanId(loan.getId()).stream()
                .anyMatch(p -> p.getStatus() == PaymentStatus.VERIFIED
                        && p.getPaidOn() != null && p.getPaidOn().isAfter(loan.getDueDate()));
    }

    /** The customer's most recent saved KYC profile (newest application first), if any. */
    private Optional<CustomerProfile> latestProfileForCustomer(Long customerId) {
        return applicationRepository.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> profileRepository.findByApplicationId(a.getId()).orElse(null))
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** The salary-credit day from the customer's most recent application that captured one. */
    private Integer latestSalaryCreditDay(Long customerId) {
        return applicationRepository.findByCustomerId(customerId).stream()
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
