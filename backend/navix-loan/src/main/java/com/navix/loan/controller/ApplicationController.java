package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.ApplicationDtos.ApplyRequest;
import com.navix.loan.dto.ApplicationDtos.AssignRequest;
import com.navix.loan.dto.ApplicationDtos.CreateApplicationRequest;
import com.navix.loan.dto.ApplicationDtos.DecisionRequest;
import com.navix.loan.dto.ApplicationDtos.EventView;
import com.navix.loan.dto.ReviewDtos.DocumentContentView;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.DocumentView;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.service.ApplicantReviewService;
import com.navix.loan.service.ApplicationFlowService;
import jakarta.validation.Valid;
import java.util.List;
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

    @PostMapping
    public ApiResponse<ApplicationView> create(@Valid @RequestBody CreateApplicationRequest request) {
        return ApiResponse.ok(ApplicationView.of(flow.createDraft(request.applicantId())));
    }

    /** Stage queue, e.g. ?status=KYC_PENDING / DISBURSEMENT_PENDING / ACCOUNTANT_PENDING. */
    @GetMapping
    public ApiResponse<List<ApplicationView>> queue(@RequestParam ApplicationStatus status) {
        return ApiResponse.ok(flow.byStatus(status).stream().map(ApplicationView::of).toList());
    }

    /** Credit Head queue: KYC-approved applications the borrower has applied on. */
    @GetMapping("/credit-queue")
    public ApiResponse<List<ApplicationView>> creditQueue() {
        return ApiResponse.ok(flow.creditHeadQueue().stream().map(ApplicationView::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> get(@PathVariable Long id) {
        return ApiResponse.ok(ApplicationView.of(flow.get(id)));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<EventView>> events(@PathVariable Long id) {
        return ApiResponse.ok(flow.events(id).stream().map(EventView::of).toList());
    }

    @PostMapping("/{id}/submit-kyc")
    public ApiResponse<ApplicationView> submitKyc(@PathVariable Long id) {
        return ApiResponse.ok(ApplicationView.of(flow.submitKyc(id)));
    }

    @PostMapping("/{id}/kyc-decision")
    public ApiResponse<ApplicationView> kycDecision(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.decideKyc(id, req.decision(), req.notes())));
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
        return ApiResponse.ok(ApplicationView.of(flow.disbursementDecision(id, req.decision(), req.notes())));
    }

    @PostMapping("/{id}/accountant-validate")
    public ApiResponse<ApplicationView> accountantValidate(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return ApiResponse.ok(ApplicationView.of(flow.accountantValidate(id, req.decision(), req.notes())));
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

    /** Any reviewing role reads the applicant's KYC details (PAN masked). */
    @GetMapping("/{id}/profile")
    public ApiResponse<ProfileView> getProfile(@PathVariable Long id) {
        return ApiResponse.ok(ProfileView.of(review.getProfile(id)));
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

    /** Fetch one document's bytes (base64) for view/download. */
    @GetMapping("/{id}/documents/{docId}")
    public ApiResponse<DocumentContentView> getDocument(@PathVariable Long id, @PathVariable Long docId) {
        return ApiResponse.ok(DocumentContentView.of(review.getDocument(id, docId)));
    }
}
