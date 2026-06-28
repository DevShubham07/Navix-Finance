package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.ApplicationDtos.ApplyRequest;
import com.navix.loan.dto.ApplicationDtos.AssignRequest;
import com.navix.loan.dto.ApplicationDtos.CreateApplicationRequest;
import com.navix.loan.dto.ApplicationDtos.DecisionRequest;
import com.navix.loan.dto.ApplicationDtos.EventView;
import com.navix.loan.dto.CreditBriefDtos.CreditBriefView;
import com.navix.loan.dto.ReviewDtos.DocumentContentView;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.DocumentUrlView;
import com.navix.loan.dto.ReviewDtos.DocumentView;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.service.ApplicantReviewService;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.CreditBriefService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single application API (dfd.md §8): create, stage queues, and the role-gated lifecycle
 * transitions. Staff identity (and thus the required role + SoD) comes from the demo actor headers
 * (X-Demo-Actor-Id / X-Demo-Actor-Role).
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationFlowService flow;
    private final ApplicantReviewService review;
    private final ApplicationVerificationService verification;
    private final CreditBriefService creditBrief;

    @PostMapping
    public ApiResponse<ApplicationView> create(@Valid @RequestBody CreateApplicationRequest request) {
        return ApiResponse.ok(ApplicationView.of(flow.createDraft(request.applicantId())));
    }

    /**
     * Returning-borrower reborrow: start a new advance reusing the saved KYC profile (applicantId
     * from the borrower actor). Routes to PRE_APPROVED (clean) or REVIEW_PENDING (past delinquency).
     */
    @PostMapping("/reborrow")
    public ApiResponse<ApplicationView> reborrow() {
        return ApiResponse.ok(ApplicationView.of(flow.reborrow()));
    }

    /** Stage queue, e.g. ?status=KYC_PENDING / DISBURSEMENT_PENDING / ACCOUNTANT_PENDING. Staff-only:
     *  rows are enriched with the applicant's credit score + 1–5★ rating (never exposed to borrowers). */
    @GetMapping
    public ApiResponse<List<ApplicationView>> queue(@RequestParam ApplicationStatus status) {
        requireStaff();
        return ApiResponse.ok(enrich(flow.byStatus(status)));
    }

    /** Credit Head queue: KYC-approved applications the borrower has applied on (credit-enriched). */
    @GetMapping("/credit-queue")
    public ApiResponse<List<ApplicationView>> creditQueue() {
        requireStaff();
        return ApiResponse.ok(enrich(flow.creditHeadQueue()));
    }

    /** The calling borrower's own applications (newest first) — for their account "loans/transactions" views. */
    @GetMapping("/mine")
    public ApiResponse<List<ApplicationView>> mine() {
        return ApiResponse.ok(flow.myApplications().stream().map(ApplicationView::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> get(@PathVariable Long id) {
        return ApiResponse.ok(ApplicationView.of(flow.get(id)));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<EventView>> events(@PathVariable Long id) {
        return ApiResponse.ok(flow.events(id).stream().map(EventView::of).toList());
    }

    /** Staff-readable verification summary for the approver review (per-step status + safe derived). */
    @GetMapping("/{id}/verifications")
    public ApiResponse<List<ApplicationVerificationService.StepResult>> verifications(@PathVariable Long id) {
        return ApiResponse.ok(verification.summary(id));
    }

    /** Staff-only credit brief: 1–5★ rating + categorized bureau facts + the CREDIT_BRIEF PDF doc id. */
    @GetMapping("/{id}/credit-brief")
    public ApiResponse<CreditBriefView> creditBrief(@PathVariable Long id) {
        requireStaff();
        return ApiResponse.ok(creditBrief.view(id));
    }

    /**
     * Borrower submits KYC (DRAFT → KYC_PENDING). Hardened gate: all mandatory verification
     * steps must be PASS/REVIEW and the agreement accepted (the onboarding-completeness check)
     * before the application enters the approver queue.
     */
    @PostMapping("/{id}/submit-kyc")
    public ApiResponse<ApplicationView> submitKyc(@PathVariable Long id) {
        if (!verification.allRequiredPassed(id)) {
            throw new BusinessException("KYC_INCOMPLETE",
                    "Complete all verification steps and accept the agreement before submitting");
        }
        return ApiResponse.ok(ApplicationView.of(flow.submitKyc(id)));
    }

    @PostMapping("/{id}/kyc-decision")
    public ApiResponse<ApplicationView> kycDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.decideKyc(id, req.decision(), req.notes())));
    }

    /** KYC approver clears (or rejects) a flagged returning borrower: REVIEW_PENDING → PRE_APPROVED. */
    @PostMapping("/{id}/review-decision")
    public ApiResponse<ApplicationView> reviewDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.decideReview(id, req.decision(), req.notes())));
    }

    @PostMapping("/{id}/apply")
    public ApiResponse<ApplicationView> apply(@PathVariable Long id, @Valid @RequestBody ApplyRequest req) {
        return ApiResponse.ok(ApplicationView.of(
                flow.apply(id, req.amountPaise(), req.purpose(), req.eligibleLimitPaise(),
                        req.salaryCreditDay())));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<ApplicationView> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.assignExecutive(id, req.executiveId())));
    }

    @PostMapping("/{id}/exec-decision")
    public ApiResponse<ApplicationView> execDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.execDecision(id, req.decision(), req.notes())));
    }

    @PostMapping("/{id}/head-decision")
    public ApiResponse<ApplicationView> headDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(
                flow.headDecision(id, req.decision(), req.approvedAmountPaise(), req.notes())));
    }

    @PostMapping("/{id}/disbursement-decision")
    public ApiResponse<ApplicationView> disbursementDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(
                flow.disbursementDecision(id, req.decision(), req.txnRef(), req.notes())));
    }

    @PostMapping("/{id}/accountant-validate")
    public ApiResponse<ApplicationView> accountantValidate(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(
                flow.accountantValidate(id, req.decision(), req.txnRef(), req.notes())));
    }

    @PostMapping("/{id}/retry-disbursement")
    public ApiResponse<ApplicationView> retry(@PathVariable Long id) {
        return ApiResponse.ok(ApplicationView.of(flow.retryDisbursement(id)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ApplicationView> cancel(@PathVariable Long id, @RequestBody(required = false) DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.cancel(id, req != null ? req.notes() : null)));
    }

    // ---- applicant review: KYC profile + documents -------------------------------------

    /** Borrower saves/updates their KYC details for this application. */
    @PutMapping("/{id}/profile")
    public ApiResponse<ProfileView> saveProfile(@PathVariable Long id, @RequestBody ProfileRequest req) {
        return ApiResponse.ok(ProfileView.of(review.saveProfile(id, req)));
    }

    /** Any reviewing role reads the applicant's KYC details (PAN masked). The staff-only credit
     *  headline (score + rating) is stripped for a borrower reading their own profile. */
    @GetMapping("/{id}/profile")
    public ApiResponse<ProfileView> getProfile(@PathVariable Long id) {
        ProfileView v = ProfileView.of(review.getProfile(id));
        if ("BORROWER".equals(ActorContext.get().role())) {
            v = v.withoutCredit();
        }
        return ApiResponse.ok(v);
    }

    /** Borrower uploads a supporting document (base64 body so it rides the JSON BFF proxy). */
    @PostMapping("/{id}/documents")
    public ApiResponse<DocumentView> addDocument(@PathVariable Long id, @Valid @RequestBody DocumentRequest req) {
        return ApiResponse.ok(DocumentView.of(review.addDocument(id, req)));
    }

    /** List an application's documents (metadata only). */
    @GetMapping("/{id}/documents")
    public ApiResponse<List<DocumentView>> listDocuments(@PathVariable Long id) {
        return ApiResponse.ok(review.listDocuments(id).stream().map(DocumentView::of).toList());
    }

    /** Fetch one document's inline bytes (base64) — legacy/demo rows only. */
    @GetMapping("/{id}/documents/{docId}")
    public ApiResponse<DocumentContentView> getDocument(@PathVariable Long id, @PathVariable Long docId) {
        return ApiResponse.ok(DocumentContentView.of(review.getDocument(id, docId)));
    }

    /** Short-lived presigned GET URL for an S3-backed document (the live approver-view path). */
    @GetMapping("/{id}/documents/{docId}/url")
    public ApiResponse<DocumentUrlView> getDocumentUrl(@PathVariable Long id, @PathVariable Long docId) {
        var doc = review.getDocument(id, docId);
        return ApiResponse.ok(new DocumentUrlView(doc.getId(), doc.getFileName(), doc.getContentType(),
                review.presignedUrl(id, docId)));
    }

    // ---- internals -----------------------------------------------------------------

    /** Attach the applicant's credit headline (score + 1–5★ + verdict) to each queue row. */
    private List<ApplicationView> enrich(List<LoanApplication> apps) {
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<Long, ApplicantProfile> byApp = review.profilesByApplicationIds(
                apps.stream().map(LoanApplication::getId).toList());
        return apps.stream().map(a -> ApplicationView.of(a, byApp.get(a.getId()))).toList();
    }

    /** Credit score / rating are staff-only — reject borrower / anonymous callers on these reads. */
    private void requireStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
    }
}
