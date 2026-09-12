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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
     *
     * <p>{@code EMPLOYMENT} is the one entry here that is not strictly a property of the person — a
     * borrower can change jobs between advances. It is carried anyway, by product decision, so a
     * repeat borrower's file still shows an EPFO employer instead of an empty card, and so a reborrow
     * does not spend a provider call on every advance. The copy is re-stamped "Carried over from
     * application N" like every other carried check, which is what tells a reviewer the employer they
     * are looking at was established earlier rather than today.
     */
    private static final Set<String> CARRIED_CHECKS = Set.of(
            ApplicationVerificationService.AADHAAR,
            ApplicationVerificationService.SELFIE,
            ApplicationVerificationService.ADDRESS,
            ApplicationVerificationService.EMPLOYMENT);

    /**
     * Application statuses that represent an already-disbursed, still-live loan. One advance at a time:
     * a returning borrower holding a live loan is <b>blocked</b> from starting a new application — they
     * must fully repay first.
     */
    private static final Set<ApplicationStatus> LIVE_LOAN_STATUSES =
            Set.of(ApplicationStatus.ACTIVE, ApplicationStatus.OVERDUE, ApplicationStatus.DEFAULTED);

    /** Cooling-off window after a self-employed auto-reject (revamp.md decision 20). */
    public static final int SELF_EMPLOYED_BLOCK_DAYS = 90;

    /** Cooling-off window after a sub-550 bureau-score auto-reject. */
    public static final int LOW_BUREAU_SCORE_BLOCK_DAYS = 90;

    /**
     * Bureau score floor: a real, numeric score below this auto-rejects the application
     * ({@code ApplicationVerificationService.pullBureau}). Do NOT confuse with
     * {@code RiskScoringService}'s {@code (bureauScore - 300) * 50 / 600} — that 600 is the width of the
     * 300-900 bureau-score span, a coincidental match, and stays untouched by this floor.
     */
    public static final int MIN_BUREAU_SCORE = 550;

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
        // An ADMIN limit override outlives the application it was set on, so it must be resolved
        // here too — a reborrow mints a NEW row and would otherwise fall back to salary (V69).
        Long eligibleLimit = eligibilityService.overrideOf(customerId)
                .orElseGet(() -> salaryPaise != null ? loanMath.eligibleLimitPaise(salaryPaise) : null);

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
        // An ADMIN limit override governs the carried ceiling (V69). Without this, a returning
        // borrower re-inherits the PRIOR sanction and the raised limit is inert — the offer journey
        // enforces sanctionedAmountPaise, not the eligible limit, so they could never draw the
        // higher amount. An explicit override is the admin's decision, up or down.
        Long limitOverride = eligibilityService.overrideOf(app.getCustomerId()).orElse(null);
        app.setSanctionedAmountPaise(
                limitOverride != null ? limitOverride : source.getSanctionedAmountPaise());
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
     *
     * <p>Public because the 24h bureau-reuse path in {@code ApplicationVerificationService} needs the
     * same carry: reusing a sibling application's pull copies the score but would otherwise leave the
     * new application with neither facts nor PDF - a score with no stars and no verdict on the staff
     * card, and nothing {@code ensureBrief} could rebuild from.
     */
    public void copyCreditBriefDocument(Long sourceAppId, Long newAppId) {
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
        Instant blockedUntil = blockDays > 0 ? Instant.now().plus(Duration.ofDays(blockDays)) : null;
        recordRejection(app, reasonCode, detail, true, blockedUntil);
        transition(app, ApplicationStatus.REJECTED, "AUTO_REJECT_" + reasonCode, detail, blockedUntil);
        return applicationRepository.save(app);
    }

    /** Outcome of {@link #reopenAfterRescore}. */
    public enum ReopenOutcome { REOPENED, SKIPPED }

    /**
     * Undoes a sub-{@value #MIN_BUREAU_SCORE} auto-reject once the bureau backfill (V57+) re-pulls a
     * clean score. ADMIN/system only — a batch override of a normally-terminal REJECTED status, never
     * a borrower- or staff-facing action.
     *
     * <p>Only reopens a file whose ORIGINAL rejecting transition came from {@code DRAFT} — that is the
     * intake-time auto-reject this backfill targets (the bureau pull is screen 9 of 10, before
     * {@code submit-kyc}). A REJECTED row whose rejecting transition came from anywhere else (e.g. a
     * later-stage staff {@code verifications/BUREAU/retry}) is left untouched, reported
     * {@link ReopenOutcome#SKIPPED} — never forced through an illegal transition.
     *
     * <p>Performs the {@code DRAFT → KYC_PENDING} transition the auto-reject denied — what
     * {@code submitKyc} would have done — by resetting the status back to {@code DRAFT} first (an
     * explicit override; {@code REJECTED} is otherwise terminal) and then running the ordinary,
     * already-legal DRAFT→KYC_PENDING transition, so the event trail reads DRAFT→KYC_PENDING, action
     * {@code REOPEN_RESCORE}, and the notification engine's generic {@code ApplicationTransitionedEvent}
     * fan-out (see {@code NotificationEventListener.mapAction}) fires without any new wiring.
     *
     * @param completenessNote non-null when the REQUIRED check set is still incomplete — appended to
     *                         the event notes so the reviewer sees it rather than discovering it later
     */
    @Transactional
    public ReopenOutcome reopenAfterRescore(Long appId, Long oldScore, Long newScore, String completenessNote) {
        requireRole("ADMIN");
        LoanApplication app = require(appId);
        if (app.getStatus() != ApplicationStatus.REJECTED || rejectionSource(appId) != ApplicationStatus.DRAFT) {
            return ReopenOutcome.SKIPPED;
        }
        clearLowBureauScoreBlock(appId);
        app.setStatus(ApplicationStatus.DRAFT);
        String notes = "score " + (oldScore == null ? "—" : oldScore) + " -> " + newScore
                + (completenessNote == null || completenessNote.isBlank() ? "" : " — " + completenessNote);
        transition(app, ApplicationStatus.KYC_PENDING, "REOPEN_RESCORE", notes);
        applicationRepository.save(app);
        return ReopenOutcome.REOPENED;
    }

    /** The status the most recent REJECTED-targeting transition on this application moved FROM. */
    private ApplicationStatus rejectionSource(Long appId) {
        return eventRepository.findByApplicationIdOrderByAtAsc(appId).stream()
                .filter(e -> e.getToStatus() == ApplicationStatus.REJECTED)
                .reduce((first, second) -> second) // latest
                .map(ApplicationEvent::getFromStatus)
                .orElse(null);
    }

    /** Clears {@code blocked_until} on the LOW_BUREAU_SCORE rejection row only — a MANUAL or
     *  SELF_EMPLOYED block on the same mobile (assertNotBlocked takes the latest) must survive. */
    private void clearLowBureauScoreBlock(Long appId) {
        rejectionRepository.findByApplicationIdAndReasonCode(appId, ApplicationRejection.LOW_BUREAU_SCORE)
                .forEach(row -> {
                    row.setBlockedUntil(null);
                    rejectionRepository.save(row);
                });
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
        // The ceiling is the STORED limit, never the one the caller posts: on the PRE_APPROVED
        // reborrow path this call fast-tracks straight to DISBURSEMENT_PENDING, so a client-supplied
        // figure would let a borrower set their own ceiling — and would overwrite an ADMIN override.
        // ApplyRequest.eligibleLimitPaise is accepted and ignored for wire compatibility.
        Long storedLimit = app.getEligibleLimit();
        if (storedLimit != null && !eligibilityService.isEligible(amountPaise, storedLimit)) {
            throw new BusinessException("LIMIT_EXCEEDED", "Requested amount exceeds the eligible limit");
        }
        app.setAmountRequested(amountPaise);
        app.setPurpose(purpose);
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
     * {@code MANUAL}. The reason is now <b>required</b> — see {@link #rejectWithBlock}.
     *
     * <p><b>Carries a {@value #MANUAL_REJECT_BLOCK_DAYS}-day cooling-off block.</b> A reject is meant
     * to be final for a while: this is the door closing, not a request for better paperwork. When the
     * problem is fixable — a stale salary slip, a statement that would not open — the reviewer is
     * expected to <em>park</em> the lead ({@link #markPending}) and ask for the document, because a
     * rejected borrower who can re-apply minutes later makes the rejection meaningless. Until this
     * block was added that is exactly what happened: a reject at 11:04 was undone by a reborrow at
     * 11:08, which sailed past KYC and credit as a pre-approved returning borrower.
     *
     * <p>Works from {@code SANCTIONED} too (ADMIN bypasses {@link #requireCreditOwnership} there) —
     * {@code SANCTIONED → REJECTED} is already legal in {@link ApplicationStatus#canTransitionTo}, and
     * nothing here narrows that.
     */
    @Transactional
    public LoanApplication rejectLead(Long appId, String remarks) {
        requireAnyRole("CREDIT_EXECUTIVE", "CREDIT_HEAD");
        LoanApplication app = require(appId);
        requireCreditOwnership(app);
        return rejectWithBlock(app, "REJECT_LEAD", remarks);
    }

    /**
     * Shared "manual reject, with a cooling-off block" path for both {@link #rejectLead} and the
     * {@link #disbursementDecision} reject branch: requires a non-blank reason (a reject with no
     * reason gave staff no record of why, and {@code adminForceDisbursementPending} already treats a
     * missing note the same way — {@code NOTE_REQUIRED}), writes one row to the rejection register,
     * and transitions to REJECTED carrying the {@value #MANUAL_REJECT_BLOCK_DAYS}-day block.
     */
    private LoanApplication rejectWithBlock(LoanApplication app, String action, String remarks) {
        if (remarks == null || remarks.isBlank()) {
            throw new BusinessException("NOTE_REQUIRED", "A reason is required to reject this application");
        }
        Instant blockedUntil = Instant.now().plus(Duration.ofDays(MANUAL_REJECT_BLOCK_DAYS));
        recordRejection(app, ApplicationRejection.MANUAL, remarks, false, blockedUntil);
        transition(app, ApplicationStatus.REJECTED, action, remarks, blockedUntil);
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

    /**
     * ADMIN-only escape hatch: force a SANCTIONED application straight to DISBURSEMENT_PENDING
     * without waiting on the borrower's own offer-acceptance journey — for a borrower who has
     * already signed their agreement but is stuck elsewhere in that journey. Gated on a
     * <b>terminal PASS</b> {@code ESIGN} row (stricter than {@link #acceptOffer}'s presence check,
     * since this bypasses the borrower's own confirmation) and a mandatory audit note.
     *
     * @throws BusinessException {@code FORBIDDEN_ROLE} for a non-ADMIN actor, {@code NOT_APPLICABLE}
     *     when the application isn't SANCTIONED, {@code AGREEMENT_NOT_SIGNED} when the ESIGN row
     *     isn't PASS, {@code NOTE_REQUIRED} when no reason is supplied
     */
    @Transactional
    public LoanApplication adminForceDisbursementPending(Long appId, String notes) {
        requireAdminOnly("Forcing an application to disbursement");
        LoanApplication app = require(appId);
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            throw new BusinessException("NOT_APPLICABLE", "This application isn't sanctioned");
        }
        boolean signed = verificationRepository
                .findByApplicationIdAndCheckType(appId, ApplicationVerificationService.ESIGN)
                .filter(v -> ApplicationVerificationService.PASS.equals(v.getStatus()))
                .isPresent();
        if (!signed) {
            throw new BusinessException("AGREEMENT_NOT_SIGNED", "The borrower hasn't signed their agreement yet");
        }
        if (notes == null || notes.isBlank()) {
            throw new BusinessException("NOTE_REQUIRED", "A reason is required to force this transition");
        }
        transition(app, ApplicationStatus.DISBURSEMENT_PENDING, "ADMIN_FORCE_DISBURSE",
                "Forced by " + ActorContext.get().name() + " — " + notes.trim());
        return applicationRepository.save(app);
    }

    /**
     * Who may force a SANCTIONED file to disbursement: ADMIN only — unlike {@link #requireRole} /
     * {@link #requireAnyRole}, ADMIN is not an addition to another named role here, it is the only
     * one allowed.
     */
    private void requireAdminOnly(String action) {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", action + " requires the ADMIN role");
        }
    }

    // ---- disbursement (W3) ---------------------------------------------------------

    /**
     * The Disbursement Head releases the money. Since V47 there is <b>no accountant hop</b>
     * (decision 42): the Head makes the transfer and records its id, so an accept without a
     * {@code txnRef} is an error rather than a hand-off — the transfer either happened, in which
     * case it has a reference, or it didn't, in which case there is nothing to accept yet.
     *
     * <p>A reject now goes through {@link #rejectWithBlock} — same as a credit reject: {@code notes}
     * is required, and it writes a rejection-register row + the {@value #MANUAL_REJECT_BLOCK_DAYS}-day
     * cooling-off block. This closes an old asymmetry: a disbursement reject used to leave no register
     * row and no cooling-off, so a rejected-at-disbursement borrower could reborrow straight back in.
     *
     * @throws BusinessException {@code TXN_REF_REQUIRED} when accepting without a transaction id,
     *     {@code NOTE_REQUIRED} when rejecting without a reason
     */
    @Transactional
    public LoanApplication disbursementDecision(Long appId, boolean accept, String txnRef, String notes) {
        requireRole("DISBURSEMENT_HEAD");
        LoanApplication app = require(appId);
        if (!accept) {
            return rejectWithBlock(app, "DISB_REJECT", notes);
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

    /** Same "dd MMM yyyy" shape the notification templates use, so both tell the borrower one date. */
    private static final java.time.format.DateTimeFormatter BLOCK_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH);

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

    /** Same as {@link #byStatus(ApplicationStatus, LocalDate, LocalDate)}, further narrowed to rows
     *  matching the free-text {@code q} (id/loan id/name/mobile/PAN) — see {@link #filterByQuery}. */
    @Transactional(readOnly = true)
    public List<LoanApplication> byStatus(ApplicationStatus status, LocalDate from, LocalDate to, String q) {
        return filterByQuery(byStatus(status, from, to), q);
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

    /** Same as {@link #creditHeadQueue(LocalDate, LocalDate)}, further narrowed to rows matching the
     *  free-text {@code q} — see {@link #filterByQuery}. */
    @Transactional(readOnly = true)
    public List<LoanApplication> creditHeadQueue(LocalDate from, LocalDate to, String q) {
        return filterByQuery(creditHeadQueue(from, to), q);
    }

    /**
     * Narrows an already status+date-filtered queue page to rows matching {@code q}: an
     * exact/prefix match on the application id or loan id (decimal string), or a case-insensitive
     * substring match on the customer's name / mobile / PAN. Blank/null {@code q} is a no-op — the
     * queue behaves exactly as before this param existed.
     *
     * // ponytail: in-memory id/loan-id/name/mobile/PAN match over the status+date window (no JPA
     * // relation from LoanApplication to CustomerProfile to push name/PAN/mobile into the
     * // Specification); push into SQL if a queue outgrows one page-fetch.
     */
    private List<LoanApplication> filterByQuery(List<LoanApplication> rows, String q) {
        if (q == null || q.isBlank() || rows.isEmpty()) {
            return rows;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        Map<Long, CustomerProfile> profileByAppId = profileRepository
                .findByApplicationIdIn(rows.stream().map(LoanApplication::getId).toList()).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        return rows.stream().filter(a -> matchesQuery(a, profileByAppId.get(a.getId()), needle)).toList();
    }

    private boolean matchesQuery(LoanApplication app, CustomerProfile profile, String needle) {
        if (String.valueOf(app.getId()).startsWith(needle)) {
            return true;
        }
        if (app.getLoanId() != null && String.valueOf(app.getLoanId()).startsWith(needle)) {
            return true;
        }
        if (profile == null) {
            return false;
        }
        return containsIgnoreCase(profile.getFullName(), needle)
                || containsIgnoreCase(profile.getMobile(), needle)
                || containsIgnoreCase(profile.getPan(), needle);
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
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
        transition(app, to, action, notes, null);
    }

    /**
     * @param retryFrom when the borrower may apply again, for a transition that just set a
     *                  cooling-off block (auto-reject / manual reject); null otherwise.
     */
    private void transition(LoanApplication app, ApplicationStatus to, String action, String notes,
                            Instant retryFrom) {
        ApplicationStatus from = app.getStatus();
        if (!from.canTransitionTo(to)) {
            log.warn("illegal transition blocked app={} {} -> {} action={}", app.getId(), from, to, action);
            throw new BusinessException("ILLEGAL_TRANSITION", from + " → " + to + " is not allowed");
        }
        logEvent(app, from, to, action, notes, retryFrom);
        app.setStatus(to);
    }

    private void logEvent(LoanApplication app, ApplicationStatus from, ApplicationStatus to,
                          String action, String notes) {
        logEvent(app, from, to, action, notes, null);
    }

    private void logEvent(LoanApplication app, ApplicationStatus from, ApplicationStatus to,
                          String action, String notes, Instant retryFrom) {
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
                action, app.getAssignedExecutiveId(), actor.id(), actor.role(), event.getAt(), retryFrom));
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
        copy.setUan(prior.getUan());
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
        // Inbox control does not become untrue between advances, and JourneyService.derive now reads
        // both flags — without these two lines every returning borrower is sent back to screen 6 to
        // re-prove addresses they already proved. V65 repairs the rows cloned before this.
        copy.setPersonalEmailVerified(prior.getPersonalEmailVerified());
        copy.setOfficialEmailOtpVerified(prior.getOfficialEmailOtpVerified());
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
     * deliberately the same neutral one the borrower saw when they were rejected, plus the date the
     * block lifts: the DATE only, never the rule or the window length, since 30 days (a reviewer's
     * call) and 90 (an engine rule) would between them say which one turned the borrower away.
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
                            "You are not eligible at the moment. You can apply again on or after "
                                    + BLOCK_DATE.format(block.getBlockedUntil().atZone(IST)) + ".");
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
