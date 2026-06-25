package com.navix.onboarding.controller;

import com.navix.common.web.ApiResponse;
import com.navix.onboarding.dto.OnboardingDtos.AdvanceStepRequest;
import com.navix.onboarding.dto.OnboardingDtos.BorrowerView;
import com.navix.onboarding.dto.OnboardingDtos.CreateBorrowerRequest;
import com.navix.onboarding.dto.OnboardingDtos.OtpRequest;
import com.navix.onboarding.dto.OnboardingDtos.OtpResponse;
import com.navix.onboarding.dto.OnboardingDtos.OtpVerifyRequest;
import com.navix.onboarding.dto.OnboardingDtos.OtpVerifyResponse;
import com.navix.onboarding.dto.OnboardingDtos.SignupApplicationView;
import com.navix.onboarding.dto.OnboardingDtos.SignupView;
import com.navix.onboarding.service.BorrowerService;
import com.navix.onboarding.service.BorrowerService.SignupResult;
import com.navix.onboarding.service.OtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrower sign-up + profile + OTP endpoints. All responses are wrapped in {@link ApiResponse}.
 * OTP is a demo in-memory flow (the issued code is echoed back since no SMS/email is wired).
 */
@RestController
@RequestMapping("/api/borrower")
@RequiredArgsConstructor
public class BorrowerController {

    private final BorrowerService borrowerService;
    private final OtpService otpService;

    /** Create a borrower and open its sign-up application. */
    @PostMapping
    public ApiResponse<SignupView> create(@Valid @RequestBody CreateBorrowerRequest request) {
        SignupResult result = borrowerService.create(request);
        return ApiResponse.ok(SignupView.of(result.borrower(), result.application()));
    }

    @GetMapping("/{id}")
    public ApiResponse<BorrowerView> getBorrower(@PathVariable Long id) {
        return ApiResponse.ok(BorrowerView.of(borrowerService.getBorrower(id)));
    }

    /** Advance the borrower's sign-up application to a given step. */
    @PostMapping("/{id}/signup/step")
    public ApiResponse<SignupApplicationView> advanceStep(@PathVariable Long id,
                                                          @Valid @RequestBody AdvanceStepRequest request) {
        return ApiResponse.ok(SignupApplicationView.of(
                borrowerService.advanceStep(id, request.step(), request.completed())));
    }

    /** Request (or resend) an OTP for a destination. Demo: the code is returned in the response. */
    @PostMapping("/otp/request")
    public ApiResponse<OtpResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
        String code = otpService.generate(request.destination());
        return ApiResponse.ok(new OtpResponse(request.destination(), code,
                "OTP generated (demo — delivered out-of-band in production)"));
    }

    /** Verify an OTP for a destination. */
    @PostMapping("/otp/verify")
    public ApiResponse<OtpVerifyResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        boolean verified = otpService.verify(request.destination(), request.code());
        return ApiResponse.ok(new OtpVerifyResponse(request.destination(), verified));
    }
}
