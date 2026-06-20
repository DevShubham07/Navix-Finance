package com.navix.onboarding.controller;

import com.navix.onboarding.service.BorrowerService;
import com.navix.onboarding.service.OtpService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrower sign-up + profile + OTP endpoints.
 * STUB: routes scaffolded; request/response DTOs and bodies are TODO.
 */
@RestController
@RequestMapping("/api/borrower")
@RequiredArgsConstructor
public class BorrowerController {

    private final BorrowerService borrowerService;
    private final OtpService otpService;

    @GetMapping("/{id}")
    public Object getBorrower(@PathVariable Long id) {
        // TODO: return borrower profile DTO.
        throw new UnsupportedOperationException("BorrowerController.getBorrower not implemented");
    }
}
