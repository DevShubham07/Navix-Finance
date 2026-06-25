package com.navix.disbursement.controller;

import com.navix.common.web.ApiResponse;
import com.navix.disbursement.dto.DisbursementDtos.CreateRequest;
import com.navix.disbursement.dto.DisbursementDtos.RequestView;
import com.navix.disbursement.dto.DisbursementDtos.StepView;
import com.navix.disbursement.service.AccountantConfirmationService;
import com.navix.disbursement.service.ApprovalChainService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the disbursement maker-checker approval chain and the manual transfer
 * confirmation. The acting staff identity is resolved server-side from the demo
 * {@code ActorContext}; separation-of-duties and the legal state machine are enforced in the
 * services. Responses use the standard {@link ApiResponse} envelope.
 */
@RestController
@RequestMapping("/api/disbursement")
@RequiredArgsConstructor
public class DisbursementController {

    private final ApprovalChainService approvalChainService;
    private final AccountantConfirmationService accountantConfirmationService;

    /** Open a disbursement request for an approved loan (→ PENDING_CREDIT_REVIEW). */
    @PostMapping("/requests")
    public ApiResponse<RequestView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(RequestView.of(approvalChainService.createRequest(request.loanId())));
    }

    /** Credit Executive recommends (→ CREDIT_RECOMMENDED). */
    @PostMapping("/requests/{id}/recommend")
    public ApiResponse<RequestView> recommend(@PathVariable UUID id) {
        return ApiResponse.ok(RequestView.of(approvalChainService.recommend(id)));
    }

    /** Credit Head approves (→ CREDIT_APPROVED); SoD vs the recommender. */
    @PostMapping("/requests/{id}/approve")
    public ApiResponse<RequestView> approve(@PathVariable UUID id) {
        return ApiResponse.ok(RequestView.of(approvalChainService.approveCredit(id)));
    }

    /** Disbursement Head authorises release (→ RELEASE_AUTHORISED); SoD vs approver + penny-drop gate. */
    @PostMapping("/requests/{id}/release")
    public ApiResponse<RequestView> release(@PathVariable UUID id) {
        return ApiResponse.ok(RequestView.of(approvalChainService.authoriseRelease(id)));
    }

    /** Accountant confirms the manual transfer (→ TRANSFER_CONFIRMED / TRANSFER_FAILED); SoD vs releaser. */
    @PostMapping("/requests/{id}/confirm")
    public ApiResponse<RequestView> confirm(@PathVariable UUID id,
                                            @RequestParam(defaultValue = "true") boolean success) {
        return ApiResponse.ok(RequestView.of(accountantConfirmationService.confirm(id, success)));
    }

    /** Current state of a disbursement request. */
    @GetMapping("/requests/{id}")
    public ApiResponse<RequestView> get(@PathVariable UUID id) {
        return ApiResponse.ok(RequestView.of(approvalChainService.getRequest(id)));
    }

    /** Full maker-checker approval trail for a request, oldest first. */
    @GetMapping("/requests/{id}/steps")
    public ApiResponse<List<StepView>> steps(@PathVariable UUID id) {
        return ApiResponse.ok(approvalChainService.getSteps(id).stream().map(StepView::of).toList());
    }
}
