package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationView;
import com.navix.loan.dto.AdminApplicationDtos.RejectionView;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.ApplicationDtos.ApplyRequest;
import com.navix.loan.dto.ApplicationDtos.AssignRequest;
import com.navix.loan.dto.ApplicationDtos.CreateApplicationRequest;
import com.navix.loan.dto.ApplicationDtos.DecisionRequest;
import com.navix.loan.dto.ApplicationDtos.EventView;
import com.navix.loan.dto.ApplicationDtos.SanctionRequest;
import com.navix.loan.dto.CreditBriefDtos.CreditBriefView;
import com.navix.loan.dto.OfferDtos;
import com.navix.loan.dto.ReviewDtos.DocumentContentView;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.DocumentUrlView;
import com.navix.loan.dto.ReviewDtos.DocumentView;
import com.navix.loan.dto.ReviewDtos.EditProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.AdminApplicationService;
import com.navix.loan.service.CustomerReviewService;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.CreditBriefService;
import com.navix.loan.service.JourneyService;
import com.navix.loan.service.OfferService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single application API: create, stage queues, and the role-gated lifecycle
 * transitions. Staff identity (and thus the required role + SoD) comes from the demo actor headers
 * (X-Demo-Actor-Id / X-Demo-Actor-Role).
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationFlowService flow;
    private final CustomerReviewService review;
    private final ApplicationVerificationService verification;
    private final CreditBriefService creditBrief;
    private final AdminApplicationService adminApplications;
    private final JourneyService journey;
    private final OfferService offer;
    private final StaffDirectory staffDirectory;
    private final LoanRepository loanRepository;
    private final ApplicationEventRepository eventRepository;

    @PostMapping
    public ApiResponse<ApplicationView> create(@Valid @RequestBody CreateApplicationRequest request) {
        return ApiResponse.ok(ApplicationView.of(flow.createDraft(request.customerId())));
    }

    /**
     * Returning-borrower reborrow: start a new advance reusing the saved KYC profile (customerId
     * from the borrower actor). Routes to PRE_APPROVED (clean) or REVIEW_PENDING (past delinquency).
     */
    @PostMapping("/reborrow")
    public ApiResponse<ApplicationView> reborrow() {
        return ApiResponse.ok(ApplicationView.of(flow.reborrow()));
    }

    /** Stage queue, e.g. ?status=KYC_PENDING / DISBURSEMENT_PENDING / ACCOUNTANT_PENDING. Staff-only:
     *  rows are enriched with the customer's credit score + 1–5★ rating (never exposed to borrowers).
     *  Optional {@code from}/{@code to} narrow to applications CREATED in that inclusive window
     *  (V53's {@code created_at}), for the live-applications Today/Yesterday/Custom filter. */
    @GetMapping
    public ApiResponse<List<ApplicationView>> queue(
            @RequestParam ApplicationStatus status,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        requireStaff();
        return ApiResponse.ok(enrich(flow.byStatus(status, from, to)));
    }

    /** Credit Head queue: KYC-approved applications the borrower has applied on (credit-enriched).
     *  Optional {@code from}/{@code to} narrow to applications CREATED in that inclusive window
     *  (V53's {@code created_at}), same as {@link #queue}, so the live-applications Today/Yesterday/
     *  Custom filter applies to this tab too. */
    @GetMapping("/credit-queue")
    public ApiResponse<List<ApplicationView>> creditQueue(
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        requireStaff();
        return ApiResponse.ok(enrich(flow.creditHeadQueue(from, to)));
    }

    /**
     * ACTIVE credit executives, for the Credit Head's assignee picker (activation gating,
     * active-role assignment rule — the credit-side twin of {@code GET /api/collections/officers}.
     *
     * <p>This exists because the picker must NOT source its list from {@code GET /api/staff}: that
     * route is ADMIN-only, so a real Credit Head got {@code FORBIDDEN_ROLE} and the picker rendered
     * an empty "No active credit executives", making assignment impossible for the one role whose
     * job it is. Returns only id/name/role/active — no emails or other staff PII.
     */
    @GetMapping("/credit-executives")
    public ApiResponse<List<StaffSummary>> assignableExecutives(
            @RequestParam(defaultValue = "CREDIT_EXECUTIVE") String role) {
        requireStaff();
        return ApiResponse.ok(staffDirectory.listActive(role));
    }

    /** Staff dashboard pipeline: application counts per status (statuses with no rows are omitted). */
    @GetMapping("/stats")
    public ApiResponse<Map<ApplicationStatus, Long>> stats() {
        requireStaff();
        return ApiResponse.ok(flow.countsByStatus());
    }

    /** The calling borrower's own applications (newest first) — for their account "loans/transactions" views. */
    @GetMapping("/mine")
    public ApiResponse<List<ApplicationView>> mine() {
        return ApiResponse.ok(flow.myApplications().stream().map(ApplicationView::of).toList());
    }

    /** ADMIN-only register: EVERY application — complete and incomplete — with full KYC detail and an
     *  onboarding-completeness summary (newest first). Non-admin callers get {@code FORBIDDEN_ROLE}. */
    @GetMapping("/all")
    public ApiResponse<List<AdminApplicationView>> all() {
        return ApiResponse.ok(adminApplications.listAll());
    }

    /** ADMIN — the rejection register (self-employed, past delinquency, manual), newest first. */
    @GetMapping("/rejections")
    public ApiResponse<List<RejectionView>> rejections(@RequestParam(required = false) String reason) {
        return ApiResponse.ok(adminApplications.listRejections(reason));
    }

    /**
     * TELECALLER/ADMIN — every pre-sanction application (work item 10), stale-first, enriched for a
     * follow-up call. Deliberately separate from {@link #all()} (ADMIN-only by design): this route
     * carries no financials and is scoped to statuses that haven't reached SANCTIONED.
     */
    @GetMapping("/telecalling")
    public ApiResponse<List<com.navix.loan.dto.TelecallingDtos.TelecallingView>> telecalling() {
        return ApiResponse.ok(adminApplications.listForTelecalling());
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> get(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        LoanApplication app = flow.get(id);
        ApplicationView view = ApplicationView.of(app);
        if (!"BORROWER".equals(ActorContext.get().role())) {
            // Staff-only: resolve the real assignee name + current-stage-entered timestamp (never
            // leaked to the borrower-facing read, mirrors /mine).
            view = view.withAssignment(resolveExecutiveName(app.getAssignedExecutiveId()),
                    latestEventAt(id));
        }
        return ApiResponse.ok(view);
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<EventView>> events(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(flow.eventViews(id));
    }

    /**
     * The two references the borrower named in Phase 3 (V46). Staff-readable — collections and the
     * disbursement desk are the reason these are captured at all, so the write path on
     * {@code OfferController} is deliberately not the only way to reach them.
     */
    @GetMapping("/{id}/references")
    public ApiResponse<List<OfferDtos.ReferenceView>> references(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(offer.references(id));
    }

    /** Staff-readable verification summary for the approver review (per-step status + safe derived). */
    @GetMapping("/{id}/verifications")
    public ApiResponse<List<ApplicationVerificationService.StepResult>> verifications(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(verification.summary(id));
    }

    /** Required-step completion snapshot for the progress tracker (Phase 3.2). Owner borrower or staff. */
    @GetMapping("/{id}/verification-progress")
    public ApiResponse<ApplicationVerificationService.VerificationProgress> verificationProgress(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(verification.progress(id));
    }

    /** Staff manual override of a verification step (KYC approver / admin) — PASS or FAIL with a note. */
    @PostMapping("/{id}/verifications/{checkType}/decision")
    public ApiResponse<ApplicationVerificationService.StepResult> manualVerificationDecision(
            @PathVariable Long id, @PathVariable String checkType, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(verification.manualDecision(id, checkType, req.decision(), req.notes()));
    }

    /** ADMIN-only retry for direct external verification APIs. */
    @PostMapping("/{id}/verifications/{checkType}/retry")
    public ApiResponse<ApplicationVerificationService.StepResult> retryVerification(@PathVariable Long id,
                                                                                      @PathVariable String checkType,
                                                                                      @RequestBody(required = false) Map<String, Object> input) {
        return ApiResponse.ok(verification.retryExternalCheck(id, checkType, input));
    }

    /** Pending-API dashboard: cross-application verification overview + status tallies (Phase 3.3). Staff. */
    @GetMapping("/verifications/overview")
    public ApiResponse<ApplicationVerificationService.VerificationOverview> verificationOverview(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String checkType,
            @RequestParam(required = false) String q) {
        requireStaff();
        return ApiResponse.ok(verification.overview(status, checkType, q));
    }

    /** Staff nudges the borrower with their pending verification steps (Phase 3.4). KYC approver / admin. */
    @PostMapping("/{id}/send-reminder")
    public ApiResponse<ApplicationVerificationService.ReminderResult> sendReminder(@PathVariable Long id) {
        return ApiResponse.ok(verification.sendKycReminder(id));
    }

    /** Staff-only credit brief: 1–5★ rating + categorized bureau facts + the CREDIT_BRIEF PDF doc id. */
    @GetMapping("/{id}/credit-brief")
    public ApiResponse<CreditBriefView> creditBrief(@PathVariable Long id) {
        requireStaff();
        return ApiResponse.ok(creditBrief.view(id));
    }

    /**
     * Borrower submits their application (DRAFT → KYC_PENDING). The gate is completeness, not
     * outcome: every intake check must have been <b>attempted</b> and the T&C accepted. A failed
     * PAN or bureau pull still reaches the credit team, flagged (revamp.md decision 10).
     */
    @PostMapping("/{id}/submit-kyc")
    public ApiResponse<ApplicationView> submitKyc(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        if (!verification.allRequiredPassed(id)) {
            throw new BusinessException("KYC_INCOMPLETE",
                    "Complete every step and accept the Terms & Conditions before submitting");
        }
        return ApiResponse.ok(ApplicationView.of(flow.submitKyc(id)));
    }

    /** Where this application is in the onboarding journey — the server answer that makes a second
     *  device resume at the right screen (revamp.md C1). */
    @GetMapping("/{id}/journey")
    public ApiResponse<JourneyService.JourneyView> journey(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(journey.current(id));
    }

    /**
     * Borrower finished a step (including skipping an optional one) — move the pointer forward.
     *
     * <p>Takes the step as a raw string and resolves it against <em>both</em> registries: the
     * Phase-1 intake ({@link JourneyService.Step}) and the Phase-3 offer journey
     * ({@link JourneyService.OfferStep}). Binding the path variable straight to the intake enum
     * silently 400'd every {@code OFFER_*} advance, and the client swallows that failure by design —
     * so the pointer never moved and cross-device resume fell back to derivation alone, which cannot
     * see the four offer screens that leave no trace of their own.
     */
    @PostMapping("/{id}/journey/{step}")
    public ApiResponse<JourneyService.JourneyView> advanceJourney(@PathVariable Long id,
                                                                 @PathVariable String step) {
        requireBorrowerOwnsOrStaff(id);
        journey.advance(id, step);
        return ApiResponse.ok(journey.current(id));
    }

    /**
     * Borrower declared self-employed at intake — auto-reject and start the cooling-off window
     * (revamp.md decisions 20, 21). The response is a plain application view; the borrower is shown a
     * neutral "not eligible" screen and never told which rule fired.
     */
    @PostMapping("/{id}/self-employed")
    public ApiResponse<ApplicationView> selfEmployed(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(ApplicationView.of(flow.autoReject(id,
                ApplicationRejection.SELF_EMPLOYED, "Declared self-employed at intake",
                ApplicationFlowService.SELF_EMPLOYED_BLOCK_DAYS)));
    }

    @PostMapping("/{id}/kyc-decision")
    public ApiResponse<ApplicationView> kycDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.decideKyc(id, req.decision(), req.notes())));
    }

    @PostMapping("/{id}/apply")
    public ApiResponse<ApplicationView> apply(@PathVariable Long id, @Valid @RequestBody ApplyRequest req) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(ApplicationView.of(
                flow.apply(id, req.amountPaise(), req.purpose(), req.eligibleLimitPaise(),
                        req.salaryCreditDay())));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<ApplicationView> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.assignExecutive(id, req.executiveId())));
    }

    /** "Accept lead" — the Credit Executive's final decision: sanction an amount + repayment date. */
    @PostMapping("/{id}/sanction")
    public ApiResponse<ApplicationView> sanction(@PathVariable Long id, @Valid @RequestBody SanctionRequest req) {
        return ApiResponse.ok(ApplicationView.of(
                flow.sanction(id, req.sanctionedAmountPaise(), req.salaryCreditDay(), req.remarks())));
    }

    /** "Reject lead" — borrower notified with no reason; remarks go to the staff-only register. */
    @PostMapping("/{id}/reject-lead")
    public ApiResponse<ApplicationView> rejectLead(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.rejectLead(id, req.notes())));
    }

    /** "Mark lead pending" — a staff-only tag + reason; the lead stays in the queue. */
    @PostMapping("/{id}/mark-pending")
    public ApiResponse<ApplicationView> markPending(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.markPending(id, req.notes())));
    }

    /** Borrower accepts the sanctioned offer → the Disbursement Head. Phase 3 fronts this with screens. */
    @PostMapping("/{id}/accept-offer")
    public ApiResponse<ApplicationView> acceptOffer(@PathVariable Long id, @RequestBody(required = false) ApplyRequest req) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(ApplicationView.of(
                flow.acceptOffer(id, req == null || req.amountPaise() == 0 ? null : req.amountPaise())));
    }

    @PostMapping("/{id}/disbursement-decision")
    public ApiResponse<ApplicationView> disbursementDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(
                flow.disbursementDecision(id, req.decision(), req.txnRef(), req.notes())));
    }

    // No accountant-validate endpoint: the Disbursement Head's transaction id IS the validation
    // (V48). The Accountant's remaining money work is repayment verification and collections
    // payments, both under /api/loan and /api/collections.

    @PostMapping("/{id}/retry-disbursement")
    public ApiResponse<ApplicationView> retry(@PathVariable Long id) {
        return ApiResponse.ok(ApplicationView.of(flow.retryDisbursement(id)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ApplicationView> cancel(@PathVariable Long id, @RequestBody(required = false) DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.cancel(id, req != null ? req.notes() : null)));
    }

    // ---- customer review: KYC profile + documents -------------------------------------

    /** Borrower saves/updates their KYC details for this application (onboarding slice-save). */
    @PutMapping("/{id}/profile")
    public ApiResponse<ProfileView> saveProfile(@PathVariable Long id, @RequestBody ProfileRequest req) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(ProfileView.of(review.saveProfile(id, req)));
    }

    /** Borrower self-edits their own profile (non-identity fields). Verification-linked edits reset the
     *  matching check; a salary change recomputes eligibility (Phase 2.2). */
    @PutMapping("/{id}/profile/self")
    public ApiResponse<ProfileView> editOwnProfile(@PathVariable Long id, @RequestBody EditProfileRequest req) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(ProfileView.of(review.editOwnProfile(id, req)));
    }

    /** Any reviewing role reads the customer's KYC details (PAN masked). The staff-only credit
     *  headline (score + rating) is stripped for a borrower reading their own profile. */
    @GetMapping("/{id}/profile")
    public ApiResponse<ProfileView> getProfile(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        ProfileView v = ProfileView.of(review.getProfile(id));
        if ("BORROWER".equals(ActorContext.get().role())) {
            v = v.withoutCredit();
        }
        return ApiResponse.ok(v);
    }

    /** Borrower uploads a supporting document (base64 body so it rides the JSON BFF proxy). */
    @PostMapping("/{id}/documents")
    public ApiResponse<DocumentView> addDocument(@PathVariable Long id, @Valid @RequestBody DocumentRequest req) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(DocumentView.of(review.addDocument(id, req)));
    }

    /** List an application's documents (metadata only). */
    @GetMapping("/{id}/documents")
    public ApiResponse<List<DocumentView>> listDocuments(@PathVariable Long id) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(review.listDocuments(id).stream().map(DocumentView::of).toList());
    }

    /** Fetch one document's inline bytes (base64) — legacy/demo rows only. */
    @GetMapping("/{id}/documents/{docId}")
    public ApiResponse<DocumentContentView> getDocument(@PathVariable Long id, @PathVariable Long docId) {
        requireBorrowerOwnsOrStaff(id);
        return ApiResponse.ok(DocumentContentView.of(review.getDocument(id, docId)));
    }

    /** Short-lived presigned GET URL for an S3-backed document (the live approver-view path). */
    @GetMapping("/{id}/documents/{docId}/url")
    public ApiResponse<DocumentUrlView> getDocumentUrl(@PathVariable Long id, @PathVariable Long docId) {
        requireBorrowerOwnsOrStaff(id);
        var doc = review.getDocument(id, docId);
        return ApiResponse.ok(new DocumentUrlView(doc.getId(), doc.getFileName(), doc.getContentType(),
                review.presignedUrl(id, docId)));
    }

    /** ADMIN deletes a document (the delete half of the CRM replace flow: delete, then re-upload). */
    @DeleteMapping("/{id}/documents/{docId}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id, @PathVariable Long docId) {
        review.deleteDocument(id, docId);
        return ApiResponse.ok(null);
    }

    // ---- internals -----------------------------------------------------------------

    /** Attach the customer's credit headline (score + 1–5★ + verdict) and, for a disbursed row, the
     *  loan's effective status + due date. Falls back to the customer's latest profile for an
     *  application without its own snapshot (e.g. a reborrow), so the headline is consistent across
     *  every staff surface. Both lookups are batched — one profile query and one loan query for the
     *  whole page, never per row. */
    private List<ApplicationView> enrich(List<LoanApplication> apps) {
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerProfile> byApp = review.effectiveProfilesByApplications(apps);
        List<Long> loanIds = apps.stream().map(LoanApplication::getLoanId).filter(Objects::nonNull).toList();
        Map<Long, Loan> byLoan = loanIds.isEmpty()
                ? Map.of()
                : StreamSupport.stream(loanRepository.findAllById(loanIds).spliterator(), false)
                        .collect(Collectors.toMap(Loan::getId, l -> l));
        Map<Long, String> nameByExecutiveId = resolveExecutiveNames(apps);
        Map<Long, java.time.Instant> stageEnteredAtByAppId = latestEventAtByAppId(
                apps.stream().map(LoanApplication::getId).toList());
        return apps.stream()
                .map(a -> ApplicationView.of(a, byApp.get(a.getId()),
                        a.getLoanId() != null ? byLoan.get(a.getLoanId()) : null)
                        .withAssignment(nameByExecutiveId.get(a.getAssignedExecutiveId()),
                                stageEnteredAtByAppId.get(a.getId())))
                .toList();
    }

    private String resolveExecutiveName(Long executiveId) {
        if (executiveId == null) {
            return null;
        }
        return staffDirectory.findStaff(executiveId).map(StaffSummary::name).orElse(null);
    }

    /** Batched name resolution for a page of applications — one lookup per distinct assignee, not per row.
     *  Built with a plain loop (not {@code Collectors.toMap}, which NPEs on a null value) since a stale
     *  assignee id that no longer resolves to a staff member is expected, not exceptional. */
    private Map<Long, String> resolveExecutiveNames(List<LoanApplication> apps) {
        Map<Long, String> result = new java.util.LinkedHashMap<>();
        for (Long executiveId : apps.stream().map(LoanApplication::getAssignedExecutiveId)
                .filter(Objects::nonNull).distinct().toList()) {
            result.put(executiveId, resolveExecutiveName(executiveId));
        }
        return result;
    }

    private java.time.Instant latestEventAt(Long applicationId) {
        return latestEventAtByAppId(List.of(applicationId)).get(applicationId);
    }

    /** Newest-first per row, so the first hit per applicationId in iteration order is that row's latest event. */
    private Map<Long, java.time.Instant> latestEventAtByAppId(List<Long> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, java.time.Instant> result = new java.util.LinkedHashMap<>();
        for (var event : eventRepository.findByApplicationIdInOrderByAtDesc(applicationIds)) {
            result.putIfAbsent(event.getApplicationId(), event.getAt());
        }
        return result;
    }

    /** Credit score / rating are staff-only — reject borrower / anonymous callers on these reads. */
    private void requireStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
    }

    /**
     * Ownership guard for the by-id reads: a BORROWER may only read their OWN application (its
     * {@code customer_id} must equal the JWT subject); any staff role may read any application.
     * Closes an IDOR — without this a borrower could fetch another customer's application, audit
     * trail, KYC profile or documents by guessing the id. (Anonymous/unauthenticated callers never
     * reach a controller method: {@code SecurityConfig} requires auth on {@code /api/**}.)
     */
    private void requireBorrowerOwnsOrStaff(Long id) {
        var actor = ActorContext.get();
        if ("BORROWER".equals(actor.role())) {
            Long owner = flow.get(id).getCustomerId();
            if (owner == null || !owner.toString().equals(actor.id())) {
                throw new BusinessException("FORBIDDEN", "Not your application");
            }
        }
    }
}
