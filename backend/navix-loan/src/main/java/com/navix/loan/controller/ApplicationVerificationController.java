package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.VerificationDtos.AddressVerifyRequest;
import com.navix.loan.dto.VerificationDtos.AgreementRequest;
import com.navix.loan.dto.VerificationDtos.BureauConsentRequest;
import com.navix.loan.dto.VerificationDtos.BureauPullRequest;
import com.navix.loan.dto.VerificationDtos.DigilockerInitRequest;
import com.navix.loan.dto.VerificationDtos.EmailVerifyRequest;
import com.navix.loan.dto.VerificationDtos.PanVerifyRequest;
import com.navix.loan.dto.VerificationDtos.PennyDropVerifyRequest;
import com.navix.loan.dto.VerificationDtos.PresignUploadRequest;
import com.navix.loan.dto.VerificationDtos.SalaryVerifyRequest;
import com.navix.loan.dto.VerificationDtos.SelfieVerifyRequest;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.ApplicationVerificationService.PresignedUpload;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrower onboarding verification API (the 9 real steps + agreement). Every endpoint
 * is borrower-only and ownership-checked: a BORROWER may act only on their own
 * application (ADMIN may act on any, for support). Results are the verification
 * service's borrower-safe {@link StepResult}s.
 */
@RestController
@RequestMapping("/api/applications/{id}/verify")
@RequiredArgsConstructor
public class ApplicationVerificationController {

    private final ApplicationVerificationService verification;
    private final ApplicationFlowService flow;

    @PostMapping("/pan")
    public ApiResponse<StepResult> pan(@PathVariable Long id, @Valid @RequestBody PanVerifyRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.verifyPan(id, req.pan()));
    }

    @PostMapping("/email")
    public ApiResponse<StepResult> email(@PathVariable Long id, @Valid @RequestBody EmailVerifyRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.verifyEmail(id, req.officialEmail()));
    }

    @PostMapping("/address")
    public ApiResponse<StepResult> address(@PathVariable Long id, @RequestBody AddressVerifyRequest req) {
        authorize(id);
        if (req.latitude() != null && req.longitude() != null) {
            return ApiResponse.ok(verification.verifyAddress(id, req.latitude(), req.longitude()));
        }
        return ApiResponse.ok(verification.recordManualAddress(id, req.manualAddress()));
    }

    @PostMapping("/digilocker/init")
    public ApiResponse<StepResult> digilockerInit(@PathVariable Long id,
                                                  @Valid @RequestBody DigilockerInitRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.digilockerInit(id, req.redirectUrl()));
    }

    @GetMapping("/digilocker/status")
    public ApiResponse<StepResult> digilockerStatus(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.digilockerStatus(id));
    }

    @PostMapping("/digilocker/complete")
    public ApiResponse<StepResult> digilockerComplete(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.digilockerComplete(id));
    }

    @PostMapping("/bureau")
    public ApiResponse<StepResult> bureau(@PathVariable Long id,
                                          @RequestBody(required = false) BureauPullRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.pullBureau(id, req == null ? null : req.otp()));
    }

    @PostMapping("/salary")
    public ApiResponse<StepResult> salary(@PathVariable Long id, @Valid @RequestBody SalaryVerifyRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.verifySalary(id, req.monthlySalaryPaise(), req.slipObjectKeys(), req.salaryCreditDay()));
    }

    /**
     * EPFO/UAN employment verification. Takes no body — every input (PAN, mobile, name, DOB, declared
     * employer) is already on the stored profile from the PAN and intake steps.
     */
    @PostMapping("/employment")
    public ApiResponse<StepResult> employment(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.verifyEmployment(id));
    }

    @PostMapping("/penny-drop")
    public ApiResponse<StepResult> pennyDrop(@PathVariable Long id, @Valid @RequestBody PennyDropVerifyRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.verifyPennyDrop(id, req.accountNumber(), req.ifsc()));
    }

    @PostMapping("/selfie")
    public ApiResponse<StepResult> selfie(@PathVariable Long id, @Valid @RequestBody SelfieVerifyRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.verifySelfie(id, req.selfieObjectKey()));
    }

    /** Primary selfie path: start the Signzy liveness video journey (returns derived.videoUrl / .fallback). */
    @PostMapping("/selfie/liveness/init")
    public ApiResponse<StepResult> selfieLivenessInit(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.selfieLivenessInit(id));
    }

    /** Poll the liveness journey result (PENDING until the borrower finishes; then PASS/REVIEW). */
    @GetMapping("/selfie/liveness/status")
    public ApiResponse<StepResult> selfieLivenessStatus(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.selfieLivenessResult(id));
    }

    @PostMapping("/agreement")
    public ApiResponse<StepResult> agreement(@PathVariable Long id, @RequestBody AgreementRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.recordAgreement(id, req.versions()));
    }

    /** Sends the bureau-consent OTP — a purpose-dedicated code, isolated from the login OTP. */
    @PostMapping("/bureau-consent/otp")
    public ApiResponse<OtpVerifierPort.OtpRequestResult> requestBureauConsentOtp(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.requestBureauConsentOtp(id));
    }

    /** OTP-verified credit-bureau consent — runs before the PAN fetch in the borrower wizard. */
    @PostMapping("/bureau-consent")
    public ApiResponse<StepResult> bureauConsent(@PathVariable Long id,
                                                 @Valid @RequestBody BureauConsentRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.recordBureauConsent(id, req.otp(), req.consentText()));
    }

    @GetMapping("/summary")
    public ApiResponse<List<StepResult>> summary(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(verification.summary(id));
    }

    /** App-scoped presigned PUT target for a browser upload (salary slip / selfie). */
    @PostMapping("/presign-upload")
    public ApiResponse<PresignedUpload> presignUpload(@PathVariable Long id,
                                                      @Valid @RequestBody PresignUploadRequest req) {
        authorize(id);
        return ApiResponse.ok(verification.presignUpload(id, req.docType(), req.fileName(), req.contentType()));
    }

    /** Borrower-only + ownership: a BORROWER may act only on their own application; ADMIN on any. */
    private void authorize(Long id) {
        String role = ActorContext.get().role();
        if (!"BORROWER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Borrower action required");
        }
        if ("BORROWER".equals(role)
                && !String.valueOf(flow.get(id).getCustomerId()).equals(ActorContext.get().id())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your application");
        }
    }
}
