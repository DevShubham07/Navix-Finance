package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.EventView;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Owns the canonical application lifecycle: one aggregate, one status, server-enforced
 * transitions with role checks, assignment-based credit ownership, and an append-only
 * {@link ApplicationEvent} trail. Credit Head and assigned Credit Executive share final decision
 * authority in the single {@code CREDIT_EXEC_PENDING} stage. The service auto-routes the
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
    // Intake rejections + their cooling-off blocks (V44).
    private final ApplicationRejectionRepository rejectionRepository;
    // Re-apply carry-over: the CREDIT_BRIEF PDF moves with the profile facts it belongs to.
    private final ApplicationDocumentRepository documentRepository;
    // The eSign gate on acceptOffer (V46) — read directly rather than through
    // ApplicationVerificationService, which already depends on this class's collaborators.
    private final ApplicationVerificationRepository verificationRepository;
    // Re-apply carry-over (V47): the two contacts move to the new application with everything else.
    private final ApplicationReferenceRepository referenceRepository;
    private final LoanMath loanMath;
    private final ApplicationEventPublisher eventPublisher;
    // Refer-a-friend: at the referred borrower's first disbursal this grants both parties their reward
    // (in-band, atomic with the loan mint). A no-op when the program is off or there's no referral.
    private final ReferralService referralService;
    private final DsaCommissionService dsaCommissionService;

    /** Loan statuses that mean money is still owed past the due date — never fully repaid. */
    private static final Set<LoanStatus> DELINQUENT_LOAN_STATUSES =
            Set.of(LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);

    /**
     * The checks a re-apply carries forward (V47). These verify the <em>person</em> — their Aadhaar
     * identity, their face, where they live — so they hold across advances. Everything else is
     * per-advance and re-run: notably {@code ESIGN}, which is consent to one specific Key Fact
     * Statement, and {@code PENNY_DROP}, which only fires if the destination account changes.
     */
    private static final Set<String> CARRIED_CHECKS = Set.of(
            ApplicationVerificationService.AADHAAR,
            ApplicationVerificationService.SELFIE,
            ApplicationVerificationService.ADDRESS);

    /**
     * Application statuses that represent an already-disbursed, still-live loan. One advance at a time:
     * a returning borrower holding a live loan is <b>blocked</b> from starting a new application — they
     * must fully repay first.
     */
    private static final Set<ApplicationStatus> LIVE_LOAN_STATUSES =
            Set.of(ApplicationStatus.ACTIVE, ApplicationStatus.OVERDUE, ApplicationStatus.DEFAULTED);

    /** Cooling-off window after a self-employed auto-reject (revamp.md decision 20). */
    public static final int SELF_EMPLOYED_BLOCK_DAYS = 90;

    /** Cooling-off window after a sub-600 bureau-score auto-reject. */
    public static final int LOW_BUREAU_SCORE_BLOCK_DAYS = 90;

    /**
     * Cooling-off window after a <b>manual</b> credit rejection ({@link #rejectLead}). Shorter than the
     * engine-rule blocks above: a reviewer's judgement call should not weigh as heavily as a hard
     * eligibility failure, but a reject still has to mean something for a month.
     */
    public static final int MANUAL_REJECT_BLOCK_DAYS = 30;

    /**
     * How many days past a due date a returning borrower may have settled and still be welcome back
     * (revamp.md decision 47). Counted from the due date itself, not from the one-day salary grace.
     */
    public static final int LATE_REPAYMENT_TOLERANCE_DAYS = 5;

    /** Staff roles permitted to cancel a pre-disbursement application (alongside the owning borrower). */
    private static final Set<String> CANCEL_STAFF_ROLES = Set.of(
            "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT");

    // ---- creation & borrower steps -------------------------------------------------

    @Transactional
    public LoanApplication createDraft(Long customerId) {
        assertCanStartNewApplication(customerId);
        LoanApplication app = new LoanApplication();
        app.setCustomerId(customerId);
        app.setCreatedAt(Instant.now());
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
     * <p>Routing is by repayment history alone — credit score does <b>not</b> gate reborrow:
     * <ul>
     *   <li>{@link #isDisqualifiedByHistory disqualified} (repaid more than
     *       {@value #LATE_REPAYMENT_TOLERANCE_DAYS} days late, or never fully repaid) → an outright
     *       auto-reject into the rejection register. There is no manual review queue behind it —
     *       V45 retired {@code REVIEW_PENDING} (decision 29);</li>
     *   <li>clean history → {@code PRE_APPROVED} and straight on to {@code SANCTIONED}, carrying the
     *       prior sanction over (decisions 45, 46) so the borrower re-walks only the short offer
     *       journey: amount → locked date → summary → sanction letter → eSign → 🎉 → account.</li>
     * </ul>
     *
     * <p>What carries over on the clean path ({@link #carryOverForReapply}): the KYC profile, the
     * sanctioned ceiling, the salary day, the DigiLocker/selfie/address evidence, the two references
     * and the disbursal account — so the shortened journey has something to skip <em>to</em>, and a
     * penny drop only fires if the borrower now types a different account. The repayment date is
     * <b>recomputed</b> from the carried salary day; carrying the old one forward would sanction a
     * date already in the past.
     *
     * <p>Deliberately <b>not</b> carried: the eSign. Every advance is signed afresh against its own
     * Key Fact Statement (decision 45) — the signature is consent to <em>these</em> terms, not a
     * standing permission.
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
        // disbursement review and the staff detail surfaces (no onboarding wizard runs on this path).
        copyProfileForReborrow(prior, app.getId());
        copyCreditBriefDocument(prior.getApplicationId(), app.getId());

        // Action is "REBORROW" for both forks — the notification listener (NotificationEventListener
        // .mapAction) keys on the action + toStatus to pick the right template.
        if (isDisqualifiedByHistory(customerId)) {
            recordRejection(app, ApplicationRejection.PAST_DELINQUENCY,
                    "Repaid more than " + LATE_REPAYMENT_TOLERANCE_DAYS
                            + " days late, or a prior advance was never fully repaid",
                    true, null);
            transition(app, ApplicationStatus.REJECTED, "REBORROW", "Past delinquency");
            return applicationRepository.save(app);
        }

        transition(app, ApplicationStatus.PRE_APPROVED, "REBORROW", "Pre-approved returning borrower");
        LoanApplication source = latestSanctionedApplication(customerId).orElse(null);
        if (source == null) {
            // Pre-approved but with nothing to re-sanction from (a legacy file that never carried a
            // credit sanction). Leave them at PRE_APPROVED — apply() still routes them to disbursement.
            return applicationRepository.save(app);
        }
        carryOverForReapply(app, source);
        transition(app, ApplicationStatus.SANCTIONED, "REAPPLY_SANCTION",
                "Carried from application " + source.getId());
        return applicationRepository.save(app);
    }

    /**
     * Copy the prior sanction and the evidence behind it onto a re-apply. Amount is deliberately left
     * unset: the ceiling carries over, the draw-down does not — the borrower picks it again on the
     * offer journey's first screen.
     */
    private void carryOverForReapply(LoanApplication app, LoanApplication source) {
        app.setReappliedFrom(source.getId());
        app.setSanctionedAmountPaise(source.getSanctionedAmountPaise());
        app.setSanctionedBy(source.getSanctionedBy());
        app.setSanctionedAt(Instant.now());
        app.setSanctionRemarks("Carried over from application " + source.getId());
        if (app.getSalaryCreditDay() == null) {
            app.setSalaryCreditDay(source.getSalaryCreditDay());
        }
        // A fresh repayment date off the carried salary day — the prior one is in the past.
        Integer salaryDay = app.getSalaryCreditDay();
        if (salaryDay != null) {
            LocalDate due = loanMath.dueDateFromSalary(LocalDate.now(), salaryDay);
            app.setApprovedRepaymentDate(due);
            app.setSanctionTenureDays((int) ChronoUnit.DAYS.between(LocalDate.now(), due));
        } else {
            app.setSanctionTenureDays(source.getSanctionTenureDays());
        }
        // Where the money went last time, so the confirm screen prefills and no penny drop fires
        // unless the borrower actually changes it (decision 45).
        app.setDisbursalAccountNumber(source.getDisbursalAccountNumber());
        app.setDisbursalIfsc(source.getDisbursalIfsc());
        app.setDisbursalHolderName(source.getDisbursalHolderName());
        app.setDisbursalBank(source.getDisbursalBank());
        // …but NOT the confirmation itself: the borrower confirms the destination every time.
        app.setDisbursalAccountChanged(Boolean.FALSE);
        app.setDisbursalAccountVerified(source.getDisbursalAccountVerified());

        copyCarriedVerifications(source.getId(), app.getId());
        copyReferences(source, app);
    }

    /**
     * Carry the identity/liveness/address evidence forward. These are properties of the person, not
     * of the advance, so re-running them on a repeat borrower buys nothing and costs a provider call
     * each. The eSign is excluded — see {@link #reborrow}.
     *
     * <p>The copies keep the original status and provider but are re-stamped with a message naming
     * the source application, so the staff Verification Dashboard shows evidence that was carried
     * over rather than passing it off as a check run today.
     */
    private void copyCarriedVerifications(Long sourceAppId, Long newAppId) {
        for (ApplicationVerification src : verificationRepository.findByApplicationIdOrderByIdAsc(sourceAppId)) {
            if (!CARRIED_CHECKS.contains(src.getCheckType())) {
                continue;
            }
            if (verificationRepository.findByApplicationIdAndCheckType(newAppId, src.getCheckType()).isPresent()) {
                continue;
            }
            ApplicationVerification copy = new ApplicationVerification();
            copy.setApplicationId(newAppId);
            copy.setCheckType(src.getCheckType());
            copy.setStatus(src.getStatus());
            copy.setProvider(src.getProvider());
            copy.setProviderTxnId(src.getProviderTxnId());
            copy.setClientRefNum(src.getClientRefNum());
            copy.setNameMatch(src.getNameMatch());
            copy.setScore(src.getScore());
            copy.setS3ObjectKey(src.getS3ObjectKey());
            copy.setDerived(src.getDerived());
            copy.setMessage("Carried over from application " + sourceAppId
                    + (src.getMessage() != null ? " — " + src.getMessage() : ""));
            verificationRepository.save(copy);
        }
    }

    /**
     * Carry the {@code CREDIT_BRIEF} PDF onto a re-apply, pointing at the <b>same</b> S3 object — no
     * re-render, no bureau call, no S3 write.
     *
     * <p>{@link #copyProfileForReborrow} already clones the rating and {@code creditBriefFacts} onto
     * the new profile, but the document row stayed behind on the source application. That left every
     * reborrow in a facts-without-PDF state, which is precisely the state
     * {@code CreditBriefService.ensureBrief} treats as "regenerate me" — so every staff read of such a
     * customer tried to write a document. Carrying the row keeps the copied facts and the copied PDF
     * consistent, and the regeneration never fires.
     */
    private void copyCreditBriefDocument(Long sourceAppId, Long newAppId) {
        if (sourceAppId == null) {
            return;
        }
        if (documentRepository.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                newAppId, CreditBriefService.DOC_TYPE).isPresent()) {
            return;
        }
        documentRepository.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                sourceAppId, CreditBriefService.DOC_TYPE).ifPresent(src -> {
            ApplicationDocument copy = new ApplicationDocument();
            copy.setApplicationId(newAppId);
            copy.setDocType(src.getDocType());
            copy.setFileName(src.getFileName());
            copy.setContentType(src.getContentType());
            copy.setSizeBytes(src.getSizeBytes());
            copy.setS3ObjectKey(src.getS3ObjectKey());
            // Legacy pre-S3 rows keep their bytes inline; carry whichever half the source used.
            copy.setData(src.getS3ObjectKey() == null ? src.getData() : null);
            documentRepository.save(copy);
        });
    }

    /** Carry the two named contacts forward; the borrower is not asked for them again. */
    private void copyReferences(LoanApplication source, LoanApplication app) {
        if (!referenceRepository.findByApplicationIdOrderBySlotAsc(app.getId()).isEmpty()) {
            return;
        }
        for (ApplicationReference src : referenceRepository.findByApplicationIdOrderBySlotAsc(source.getId())) {
            ApplicationReference copy = new ApplicationReference();
            copy.setApplicationId(app.getId());
            copy.setCustomerId(app.getCustomerId());
            copy.setSlot(src.getSlot());
            copy.setFullName(src.getFullName());
            copy.setMobile(src.getMobile());
            copy.setRelation(src.getRelation());
            referenceRepository.save(copy);
        }
    }

    /** The customer's most recent application that actually carried a credit sanction. */
    private Optional<LoanApplication> latestSanctionedApplication(Long customerId) {
        return applicationRepository.findByCustomerId(customerId).stream()
                .filter(a -> a.getSanctionedAmountPaise() != null)
                .max(Comparator.comparing(LoanApplication::getId));
    }

    @Transactional
    public LoanApplication submitKyc(Long appId) {
        requireRole("BORROWER");
        LoanApplication app = require(appId);
        transition(app, ApplicationStatus.KYC_PENDING, "SUBMIT_KYC", null);
        return applicationRepository.save(app);
    }

    /**
     * Engine rejection during intake (V44): records the reason in the register, optionally blocks the
     * borrower's mobile for {@code blockDays}, and moves the draft to REJECTED. Today's only caller is
     * the self-employed gate; Phase 4 adds past delinquency. The borrower is never told which rule
     * fired — the caller returns the same neutral "not eligible" message either way.
     */
    @Transactional
    public LoanApplication autoReject(Long appId, String reasonCode, String detail, int blockDays) {
        requireRole("BORROWER");
        LoanApplication app = require(appId);
        recordRejection(app, reasonCode, detail, true,
                blockDays > 0 ? Instant.now().plus(Duration.ofDays(blockDays)) : null);
        transition(app, ApplicationStatus.REJECTED, "AUTO_REJECT_" + reasonCode, detail);
        return applicationRepository.save(app);
    }

    /** Append one row to the rejection register, resolving the mobile the block is keyed on. */
    @Transactional
    public void recordRejection(LoanApplication app, String reasonCode, String detail,
                                boolean auto, Instant blockedUntil) {
        ApplicationRejection row = new ApplicationRejection();
        row.setApplicationId(app.getId());
        row.setCustomerId(app.getCustomerId());
        row.setMobile(mobileOf(app));
        row.setReasonCode(reasonCode);
        row.setReasonDetail(detail);
        row.setAuto(auto);
        row.setBlockedUntil(blockedUntil);
        rejectionRepository.save(row);
    }

    /**
     * Explicit KYC clearance. The dedicated KYC_APPROVER role was deleted in V45 — the credit team
     * absorbed it — and the live path now runs {@code KYC_PENDING → assign → CREDIT_EXEC_PENDING}
     * without this stop. Retained because a reviewer may still want to record a KYC reject.
     */
    @Transactional
    public LoanApplication decideKyc(Long appId, boolean approve, String notes) {
        requireAnyRole("CREDIT_HEAD", "CREDIT_EXECUTIVE");
        LoanApplication app = require(appId);
        if (approve) {
            transition(app, ApplicationStatus.KYC_APPROVED, "KYC_APPROVE", notes);
        } else {
            transition(app, ApplicationStatus.KYC_REJECTED, "KYC_REJECT", notes);
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
        // No amount check: since Phase 1 the borrower never names an amount — the Credit Executive
        // sets the sanctioned figure. The old NOT_APPLIED gate would block every intake.
        // Activation gating: the assignee must be an ACTIVE Credit Executive or the acting Head.
        // ADMIN is exempt — oversight may self-assign and drive the credit step solo (per-step control).
        CurrentActor actor = ActorContext.get();
        Long actorId = actorIdOrNull();
        boolean selfAssignment = "CREDIT_HEAD".equals(actor.role()) && executiveId.equals(actorId);
        if (!"ADMIN".equals(actor.role()) && !selfAssignment
                && !staffDirectory.isActiveWithRole(executiveId, "CREDIT_EXECUTIVE")) {
            throw new BusinessException("INVALID_ASSIGNEE",
                    "The assignee must be the Credit Head or an active Credit Executive");
        }
        Long previous = app.getAssignedExecutiveId();
        app.setAssignedExecutiveId(executiveId);
        if (app.getStatus() == ApplicationStatus.CREDIT_EXEC_PENDING) {
            logEvent(app, app.getStatus(), app.getStatus(), "REASSIGN",
                    "previousExecutiveId=" + previous + " newExecutiveId=" + executiveId);
        } else {
            transition(app, ApplicationStatus.CREDIT_EXEC_PENDING, "ASSIGN", "executiveId=" + executiveId);
        }
        return applicationRepository.save(app);
    }

    /**
     * "Accept lead" — the Credit Executive's <b>final</b> credit decision (V45, revamp.md decision 27).
     * Sanctions a ceiling and the repayment date the borrower will be held to, and moves the
     * application to {@link ApplicationStatus#SANCTIONED} where the borrower walks the post-approval
     * journey. There is no Head counter-approval: the Head's role is to assign the work.
     *
     * <p>No 25%-of-salary cap (decision 33) — the executive types the amount. The eligible limit
     * computed from salary survives only as on-screen guidance.
     */
    @Transactional
    public LoanApplication sanction(Long appId, long sanctionedAmountPaise, Integer salaryCreditDay,
                                    String remarks) {
        requireAnyRole("CREDIT_EXECUTIVE", "CREDIT_HEAD");
        LoanApplication app = require(appId);
        requireCreditOwnership(app);
        if (sanctionedAmountPaise < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "The sanctioned amount is below the minimum of ₹1,000");
        }
        if (salaryCreditDay == null || salaryCreditDay < 1 || salaryCreditDay > 31) {
            throw new BusinessException("INVALID_SALARY_DAY", "Salary credit day must be between 1 and 31");
        }
        LocalDate projectedRepaymentDate = loanMath.dueDateFromSalary(LocalDate.now(), salaryCreditDay);
        app.setSanctionedAmountPaise(sanctionedAmountPaise);
        app.setApprovedRepaymentDate(projectedRepaymentDate);
        app.setSanctionTenureDays((int) java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.now(), projectedRepaymentDate));
        app.setSanctionRemarks(remarks);
        app.setSanctionedBy(actorIdOrNull());
        app.setSanctionedAt(Instant.now());
        app.setSalaryCreditDay(salaryCreditDay);
        // Clearing the pending tag: a sanctioned lead is no longer parked.
        app.setMarkedPendingAt(null);
        app.setPendingReason(null);
        transition(app, ApplicationStatus.SANCTIONED, "SANCTION",
                "amountPaise=" + sanctionedAmountPaise + " salaryCreditDay=" + salaryCreditDay
                        + " projectedRepaymentDate=" + projectedRepaymentDate
                        + (remarks == null || remarks.isBlank() ? "" : " — " + remarks));
        return applicationRepository.save(app);
    }

    /**
     * "Reject lead" — a staff credit rejection. The borrower is notified but <b>never told why</b>
     * (decision 31); the executive's remarks go to the staff-only rejection register tagged
     * {@code MANUAL}.
     *
     * <p><b>Carries a {@value #MANUAL_REJECT_BLOCK_DAYS}-day cooling-off block.</b> A reject is meant
     * to be final for a while: this is the door closing, not a request for better paperwork. When the
     * problem is fixable — a stale salary slip, a statement that would not open — the reviewer is
     * expected to <em>park</em> the lead ({@link #markPending}) and ask for the document, because a
     * rejected borrower who can re-apply minutes later makes the rejection meaningless. Until this
     * block was added that is exactly what happened: a reject at 11:04 was undone by a reborrow at
     * 11:08, which sailed past KYC and credit as a pre-approved returning borrower.
     */
    @Transactional
    public LoanApplication rejectLead(Long appId, String remarks) {
        requireAnyRole("CREDIT_EXECUTIVE", "CREDIT_HEAD");
        LoanApplication app = require(appId);
        requireCreditOwnership(app);
        recordRejection(app, ApplicationRejection.MANUAL, remarks, false,
                Instant.now().plus(Duration.ofDays(MANUAL_REJECT_BLOCK_DAYS)));
        transition(app, ApplicationStatus.REJECTED, "REJECT_LEAD", remarks);
        return applicationRepository.save(app);
    }

    /**
     * "Mark lead pending" — a tag + reason, nothing more (decision 30). The application keeps its
     * status and its place in the queue, and the borrower is not notified; it exists so an executive
     * can park a file they're chasing information on without it looking untouched.
     */
    @Transactional
    public LoanApplication markPending(Long appId, String reason) {
        requireAnyRole("CREDIT_EXECUTIVE", "CREDIT_HEAD");
        LoanApplication app = require(appId);
        requireCreditOwnership(app);
        app.setMarkedPendingAt(Instant.now());
        app.setPendingReason(reason);
        // Same-status event: an audit entry, not a transition.
        logEvent(app, app.getStatus(), app.getStatus(), "MARK_PENDING", reason);
        return applicationRepository.save(app);
    }

    /**
     * The borrower accepts the sanctioned offer, ending the post-approval journey and handing the
     * file to the Disbursement Head. Phase 3's offer screens (amount → eSign → disbursal account) sit
     * in front of this call, and {@code OfferService.confirmDisbursalAccount} is its normal caller.
     *
     * <p>Gated on a terminal {@code ESIGN} row. Phase 3's identity checks deliberately pass through
     * silently (revamp.md decision 11), but the signature is not a check — it is the borrower's
     * agreement to the Key Fact Statement, and without it this endpoint would be a way to reach
     * disbursement having signed nothing.
     */
    @Transactional
    public LoanApplication acceptOffer(Long appId, Long amountPaise) {
        requireRole("BORROWER");
        LoanApplication app = require(appId);
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            throw new BusinessException("NOT_APPLICABLE", "This offer isn't ready to accept");
        }
        if (verificationRepository
                .findByApplicationIdAndCheckType(appId, ApplicationVerificationService.ESIGN)
                .isEmpty()) {
            throw new BusinessException("ESIGN_REQUIRED", "Sign your sanction letter before continuing");
        }
        long ceiling = app.getSanctionedAmountPaise() != null ? app.getSanctionedAmountPaise() : 0L;
        long drawn = amountPaise != null ? amountPaise : ceiling;
        if (drawn < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "Requested amount is below the minimum of ₹1,000");
        }
        if (drawn > ceiling) {
            throw new BusinessException("LIMIT_EXCEEDED", "Requested amount exceeds the sanctioned amount");
        }
        app.setAmountRequested(drawn);
        transition(app, ApplicationStatus.DISBURSEMENT_PENDING, "ACCEPT_OFFER", "amountPaise=" + drawn);
        return applicationRepository.save(app);
    }

    // ---- disbursement (W3) ---------------------------------------------------------

    /**
     * The Disbursement Head releases the money. Since V47 there is <b>no accountant hop</b>
     * (decision 42): the Head makes the transfer and records its id, so an accept without a
     * {@code txnRef} is an error rather than a hand-off — the transfer either happened, in which
     * case it has a reference, or it didn't, in which case there is nothing to accept yet.
     *
     * @throws BusinessException {@code TXN_REF_REQUIRED} when accepting without a transaction id
     */
    @Transactional
    public LoanApplication disbursementDecision(Long appId, boolean accept, String txnRef, String notes) {
        requireRole("DISBURSEMENT_HEAD");
        LoanApplication app = require(appId);
        if (!accept) {
            transition(app, ApplicationStatus.REJECTED, "DISB_REJECT", notes);
        } else if (txnRef != null && !txnRef.isBlank()) {
            finalizeDisbursal(app, txnRef, notes);
        } else {
            throw new BusinessException("TXN_REF_REQUIRED",
                    "Enter the transaction id of the transfer to release this loan");
        }
        return applicationRepository.save(app);
    }

    /**
     * Mint the loan and activate the application (DISBURSED → ACTIVE), recording the disbursal
     * transaction id supplied by the Disbursement Head.
     */
    private void finalizeDisbursal(LoanApplication app, String txnRef, String notes) {
        transition(app, ApplicationStatus.DISBURSED, "VALIDATE_SUCCESS", notes);
        Loan loan = loanService.disburse(app, LocalDate.now(), txnRef);
        app.setLoanId(loan.getId());
        // Refer-a-friend reward: if this borrower was referred and this is their first disbursal, grant
        // both parties their ₹reward (creates the pending payouts) — atomic with the loan mint.
        referralService.onLoanDisbursed(app.getCustomerId(), loan.getId());
        // DSA commission: if this borrower's PAN matches a DSA lead entered before this
        // application and this is their first loan, accrue the 3.5% commission — atomic with the
        // loan mint. Never throws (see DsaCommissionService.onLoanDisbursed javadoc).
        dsaCommissionService.onLoanDisbursed(app, loan);
        transition(app, ApplicationStatus.ACTIVE, "ACTIVATE", "loanId=" + loan.getId());
    }

    /**
     * Put a failed transfer back on the Disbursement Head's desk. Before V47 this returned the file
     * to the accountant; with the accountant hop gone, the Head who owns the release retries it.
     */
    @Transactional
    public LoanApplication retryDisbursement(Long appId) {
        requireRole("DISBURSEMENT_HEAD");
        LoanApplication app = require(appId);
        transition(app, ApplicationStatus.DISBURSEMENT_PENDING, "RETRY", null);
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
        LoanApplication app = require(appId);
        requireCreditOwnership(app);
        return app;
    }

    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    @Transactional(readOnly = true)
    public List<LoanApplication> byStatus(ApplicationStatus status) {
        return byStatus(status, null, null);
    }

    /**
     * Stage queue, newest first, optionally narrowed to applications CREATED in the inclusive
     * {@code [from, to]} window (V53's {@code created_at}). Dates are interpreted in IST — the
     * product's only operating timezone — so "Today" means today in Delhi, not UTC.
     */
    @Transactional(readOnly = true)
    public List<LoanApplication> byStatus(ApplicationStatus status, LocalDate from, LocalDate to) {
        boolean isExecutive = "CREDIT_EXECUTIVE".equals(ActorContext.get().role());
        Long execId = isExecutive ? actorIdOrNull() : null;
        if (isExecutive && execId == null) {
            return List.of();
        }
        Instant fromInst = from == null ? null : from.atStartOfDay(IST).toInstant();
        Instant toInst = to == null ? null : to.plusDays(1).atStartOfDay(IST).toInstant();
        org.springframework.data.jpa.domain.Specification<LoanApplication> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.equal(root.get("status"), status));
            if (execId != null) {
                predicates.add(cb.equal(root.get("assignedExecutiveId"), execId));
            }
            if (fromInst != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInst));
            }
            if (toInst != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toInst));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort
                .by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                .and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
        return applicationRepository.findAll(spec, sort);
    }

    /**
     * Credit Head queue: submitted intakes awaiting assignment. Since V45 the queue is driven by
     * KYC_PENDING (the borrower no longer names an amount, so the old "has applied" filter would
     * have emptied it); KYC_APPROVED rows from before the change are still listed.
     */
    @Transactional(readOnly = true)
    public List<LoanApplication> creditHeadQueue() {
        return creditHeadQueue(null, null);
    }

    /**
     * Same as {@link #creditHeadQueue()}, optionally narrowed to applications CREATED in the
     * inclusive {@code [from, to]} window (IST) — so the live-applications Today/Yesterday/Custom
     * filter applies here too, not just to the plain {@code byStatus} panels.
     */
    @Transactional(readOnly = true)
    public List<LoanApplication> creditHeadQueue(LocalDate from, LocalDate to) {
        requireRole("CREDIT_HEAD");
        Instant fromInst = from == null ? null : from.atStartOfDay(IST).toInstant();
        Instant toInst = to == null ? null : to.plusDays(1).atStartOfDay(IST).toInstant();
        return java.util.stream.Stream.concat(
                        applicationRepository.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.KYC_PENDING).stream(),
                        applicationRepository.findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus.KYC_APPROVED).stream())
                .filter(a -> fromInst == null || !a.getCreatedAt().isBefore(fromInst))
                .filter(a -> toInst == null || a.getCreatedAt().isBefore(toInst))
                .sorted(Comparator.comparing(LoanApplication::getCreatedAt)
                        .thenComparing(LoanApplication::getId).reversed())
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

    /**
     * The application's audit trail enriched with each actor's display <b>name</b> (never just the
     * role): a {@code BORROWER}-role event resolves to the customer's {@link CustomerProfile#getFullName()},
     * any other role to {@link StaffDirectory#findStaff(Long)}'s name. Unresolvable actors (unknown
     * staff id, no profile, non-numeric id) yield a {@code null} name — this never throws. Staff-name
     * lookups are memoized per call so a trail with repeated actors hits the directory once each.
     */
    @Transactional(readOnly = true)
    public List<EventView> eventViews(Long appId) {
        LoanApplication app = require(appId);
        // Prefer the profile snapshot bound to THIS application; fall back (lazily — the common
        // case resolves on the app-bound row) to the customer's latest profile.
        String appBorrowerName = profileRepository.findByApplicationId(appId)
                .map(CustomerProfile::getFullName)
                .filter(n -> n != null && !n.isBlank())
                .or(() -> latestProfileForCustomer(app.getCustomerId())
                        .map(CustomerProfile::getFullName)
                        .filter(n -> n != null && !n.isBlank()))
                .orElse(null);
        Map<String, String> staffNameCache = new HashMap<>();
        return events(appId).stream()
                .map(e -> EventView.of(e, resolveActorName(e, appBorrowerName, staffNameCache)))
                .toList();
    }

    /** Resolve one event's actor to a display name (borrower → profile name, else staff directory);
     *  never throws — an unresolvable actor returns {@code null}. */
    private String resolveActorName(ApplicationEvent e, String borrowerName, Map<String, String> staffNameCache) {
        if ("BORROWER".equalsIgnoreCase(e.getActorRole())) {
            return borrowerName;
        }
        String actorId = e.getActorId();
        if (actorId == null) {
            return null;
        }
        if (staffNameCache.containsKey(actorId)) {
            return staffNameCache.get(actorId);
        }
        String name;
        try {
            name = staffDirectory.findStaff(Long.valueOf(actorId)).map(StaffSummary::name).orElse(null);
        } catch (NumberFormatException ex) {
            name = null;
        }
        staffNameCache.put(actorId, name);
        return name;
    }

    /** Per-status application counts for the staff dashboard pipeline (statuses with no rows are omitted). */
    @Transactional(readOnly = true)
    public Map<ApplicationStatus, Long> countsByStatus() {
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (LoanApplicationRepository.StatusCount row : applicationRepository.countGroupByStatus()) {
            counts.put(row.getStatus(), row.getCount());
        }
        return counts;
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
        requireAnyRole(role);
    }

    /** Any-of role gate. ADMIN always passes (oversight). */
    private void requireAnyRole(String... roles) {
        String actual = ActorContext.get().role();
        if ("ADMIN".equals(actual)) {
            return;
        }
        for (String role : roles) {
            if (role.equals(actual)) {
                return;
            }
        }
        throw new BusinessException("FORBIDDEN_ROLE",
                "This action requires role " + String.join(" or ", roles));
    }

    /** The acting staff id, or null when it isn't numeric (borrower/anonymous paths). */
    private Long actorIdOrNull() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void requireCreditOwnership(LoanApplication app) {
        if (!"CREDIT_EXECUTIVE".equals(ActorContext.get().role())) {
            return;
        }
        Long actorId = actorIdOrNull();
        if (actorId == null || !actorId.equals(app.getAssignedExecutiveId())) {
            throw new BusinessException("CREDIT_FILE_NOT_ASSIGNED",
                    "This credit file is not assigned to you");
        }
    }

    // ---- reborrow standing (computed from history; no stored flag) -----------------

    /**
     * One advance at a time: a borrower may not start a new application while they hold a live loan
     * (they must fully repay it first) or while a previous pre-disbursement application is still in
     * flight. Server-enforced so a direct create call can't bypass the UI gating.
     */
    private void assertCanStartNewApplication(Long customerId) {
        assertNotBlocked(customerId);
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        if (apps.stream().anyMatch(a -> LIVE_LOAN_STATUSES.contains(a.getStatus()))) {
            throw new BusinessException("ACTIVE_LOAN", "Repay your current advance before borrowing again");
        }
        if (apps.stream().anyMatch(a -> !a.getStatus().isTerminal() && !LIVE_LOAN_STATUSES.contains(a.getStatus()))) {
            throw new BusinessException("ACTIVE_APPLICATION", "Finish your in-progress application before starting a new one");
        }
    }

    /**
     * The engine rule that turns a returning borrower away (V47; revamp.md decision 47).
     *
     * <p>Being <em>a bit</em> late is not disqualifying — salary lands when it lands, and the
     * product already prices those days as a 2%/day penalty. What disqualifies is a borrower who
     * either dragged a loan <b>more than {@value #LATE_REPAYMENT_TOLERANCE_DAYS} days past its due
     * date</b> before clearing it, or never cleared it at all:
     * <ul>
     *   <li>a prior application that ended DEFAULTED or WRITTEN_OFF;</li>
     *   <li>a loan still outstanding past its due date (OVERDUE / IN_COLLECTIONS);</li>
     *   <li>a loan whose last verified payment landed more than the tolerance past the due date.</li>
     * </ul>
     *
     * <p>The tolerance is counted from the <b>due date</b>, not from the one-day salary grace — the
     * grace exists so a payment on salary-day+1 isn't <i>penalised</i>, not to extend how long a
     * borrower may run late before it counts against them.
     *
     * <p>This replaced a much broader predicate ("ever overdue, or any payment after the due date"),
     * which under V45 auto-rejects would have turned away anyone who was ever a single day late.
     */
    private boolean isDisqualifiedByHistory(Long customerId) {
        boolean everWrittenOff = applicationRepository.findByCustomerId(customerId).stream()
                .anyMatch(a -> a.getStatus() == ApplicationStatus.DEFAULTED
                        || a.getStatus() == ApplicationStatus.WRITTEN_OFF);
        if (everWrittenOff) {
            return true;
        }
        LocalDate today = LocalDate.now();
        for (Loan loan : loanRepository.findByCustomerId(customerId)) {
            if (loan.getDueDate() == null) {
                continue;
            }
            // Still owed money after the due date — the loan was never fully repaid.
            if (DELINQUENT_LOAN_STATUSES.contains(loan.effectiveStatus(today))) {
                return true;
            }
            if (paidBeyondTolerance(loan)) {
                return true;
            }
        }
        return false;
    }

    /** True if any verified repayment landed more than the tolerance past the loan's due date. */
    private boolean paidBeyondTolerance(Loan loan) {
        LocalDate cutoff = loan.getDueDate().plusDays(LATE_REPAYMENT_TOLERANCE_DAYS);
        return paymentRepository.findByLoanId(loan.getId()).stream()
                .anyMatch(p -> p.getStatus() == PaymentStatus.VERIFIED
                        && p.getPaidOn() != null && p.getPaidOn().isAfter(cutoff));
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
        // The V44 intake fields. The salary account is load-bearing, not just informational: the
        // Phase-3 disbursal-account screen decides "did the borrower change account?" by comparing
        // against it, so dropping it here made every re-apply look like a changed account and fired
        // a penny drop the borrower never asked for (revamp.md decision 45).
        copy.setOfficialEmail(prior.getOfficialEmail());
        copy.setSalaryAccountNumber(prior.getSalaryAccountNumber());
        copy.setSalaryIfsc(prior.getSalaryIfsc());
        copy.setSalaryAccountMobile(prior.getSalaryAccountMobile());
        copy.setPreviousSalaryDate(prior.getPreviousSalaryDate());
        // Terms are accepted once per borrower and the PEP declaration stands; re-prompting a
        // returning borrower for both would be theatre.
        copy.setTermsVersion(prior.getTermsVersion());
        copy.setTermsAcceptedAt(prior.getTermsAcceptedAt());
        copy.setPepDeclaredAt(prior.getPepDeclaredAt());
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

    /**
     * Cooling-off gate (V44): a mobile turned away by an engine rule can't start a new application
     * until the block expires — including by re-answering the employment question. The message is
     * deliberately the same neutral one the borrower saw when they were rejected.
     */
    private void assertNotBlocked(Long customerId) {
        String mobile = latestProfileForCustomer(customerId).map(CustomerProfile::getMobile).orElse(null);
        if (mobile == null || mobile.isBlank()) {
            return;
        }
        rejectionRepository.findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(mobile, Instant.now())
                .ifPresent(block -> {
                    log.info("blocked application start customer={} until={}", customerId, block.getBlockedUntil());
                    throw new BusinessException("NOT_ELIGIBLE",
                            "You are not eligible at the moment. Please try again later.");
                });
    }

    /** The mobile on this application's profile, else the customer's most recent one. */
    private String mobileOf(LoanApplication app) {
        return profileRepository.findByApplicationId(app.getId())
                .map(CustomerProfile::getMobile)
                .filter(m -> m != null && !m.isBlank())
                .or(() -> latestProfileForCustomer(app.getCustomerId()).map(CustomerProfile::getMobile))
                .orElse(null);
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
