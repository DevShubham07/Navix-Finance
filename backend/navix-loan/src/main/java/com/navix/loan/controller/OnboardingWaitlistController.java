package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.OnboardingWaitlistService;
import com.navix.loan.service.OnboardingWaitlistService.GateView;
import com.navix.loan.service.OnboardingWaitlistService.SubmissionView;
import com.navix.loan.service.OnboardingWaitlistService.SubmitRequest;
import com.navix.loan.service.OnboardingWaitlistService.WaitlistRow;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Waitlist mode (V76). The borrower pair is authenticated (any bearer, role checked in the service);
 * the staff list sits under {@code /api/staff/**} so {@code SecurityConfig} already demands the staff
 * audience; {@code /api/auth/borrower/onboarding} is deliberately anonymous — the logged-out signup
 * screen has to know whether to skip straight to OTP — and exposes exactly one boolean.
 */
@RestController
@RequiredArgsConstructor
public class OnboardingWaitlistController {

    private final OnboardingWaitlistService service;

    @GetMapping("/api/auth/borrower/onboarding")
    public ApiResponse<Map<String, Boolean>> paused() {
        return ApiResponse.ok(Map.of("paused", service.paused()));
    }

    @GetMapping("/api/onboarding-waitlist")
    public ApiResponse<GateView> gate() {
        return ApiResponse.ok(service.gate());
    }

    @PostMapping("/api/onboarding-waitlist")
    public ApiResponse<SubmissionView> submit(@RequestBody SubmitRequest req) {
        return ApiResponse.ok(service.submit(req));
    }

    @GetMapping("/api/staff/onboarding-waitlist")
    public ApiResponse<List<WaitlistRow>> list(@RequestParam(required = false) String q) {
        return ApiResponse.ok(service.list(q));
    }
}
