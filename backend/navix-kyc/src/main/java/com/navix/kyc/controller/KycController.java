package com.navix.kyc.controller;

import com.navix.common.web.ApiResponse;
import com.navix.kyc.dto.KycDtos.CaseView;
import com.navix.kyc.dto.KycDtos.CheckView;
import com.navix.kyc.dto.KycDtos.DigiLockerSessionRequest;
import com.navix.kyc.dto.KycDtos.DigiLockerSessionView;
import com.navix.kyc.dto.KycDtos.StartCaseRequest;
import com.navix.kyc.dto.KycDtos.SubmitCheckRequest;
import com.navix.kyc.entity.KycCase;
import com.navix.kyc.service.KycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KYC endpoints (cases, checks, approver decisions, DigiLocker sessions). All responses are
 * wrapped in {@link ApiResponse}. For the demo, check results are submitted via the API.
 */
@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;

    @GetMapping("/case/{borrowerId}")
    public ApiResponse<CaseView> getCase(@PathVariable Long borrowerId) {
        KycCase kycCase = kycService.getCase(borrowerId);
        return ApiResponse.ok(CaseView.of(kycCase, kycService.getChecks(kycCase.getId())));
    }

    /** Open (or fetch) the KYC case for a borrower. */
    @PostMapping("/case")
    public ApiResponse<CaseView> startCase(@Valid @RequestBody StartCaseRequest request) {
        KycCase kycCase = kycService.openCase(request.borrowerId());
        return ApiResponse.ok(CaseView.of(kycCase, kycService.getChecks(kycCase.getId())));
    }

    /** Submit a single check result; the case status is recomputed. */
    @PostMapping("/case/{borrowerId}/check")
    public ApiResponse<CheckView> submitCheck(@PathVariable Long borrowerId,
                                              @Valid @RequestBody SubmitCheckRequest request) {
        return ApiResponse.ok(CheckView.of(
                kycService.submitCheck(borrowerId, request.type(), request.result(), request.score())));
    }

    /** Record a DigiLocker session outcome against the borrower's case. */
    @PostMapping("/case/{borrowerId}/digilocker")
    public ApiResponse<DigiLockerSessionView> recordDigiLocker(@PathVariable Long borrowerId,
                                                               @Valid @RequestBody DigiLockerSessionRequest request) {
        return ApiResponse.ok(DigiLockerSessionView.of(
                kycService.recordDigiLockerSession(borrowerId, request.clientId(), request.status(),
                        request.aadhaarLinked())));
    }

    /** Approver decision: approve the case. */
    @PostMapping("/case/{borrowerId}/approve")
    public ApiResponse<CaseView> approve(@PathVariable Long borrowerId) {
        KycCase kycCase = kycService.approve(borrowerId);
        return ApiResponse.ok(CaseView.of(kycCase, kycService.getChecks(kycCase.getId())));
    }

    /** Approver decision: reject the case. */
    @PostMapping("/case/{borrowerId}/reject")
    public ApiResponse<CaseView> reject(@PathVariable Long borrowerId) {
        KycCase kycCase = kycService.reject(borrowerId);
        return ApiResponse.ok(CaseView.of(kycCase, kycService.getChecks(kycCase.getId())));
    }
}
