package com.navix.kyc.controller;

import com.navix.kyc.service.DigiLockerSessionService;
import com.navix.kyc.service.KycService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KYC endpoints (cases, checks, DigiLocker sessions).
 * STUB: routes scaffolded; DTOs and bodies are TODO.
 */
@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;
    private final DigiLockerSessionService digiLockerSessionService;

    @GetMapping("/case/{borrowerId}")
    public Object getCase(@PathVariable Long borrowerId) {
        // TODO: return KYC case summary DTO for the borrower.
        throw new UnsupportedOperationException("KycController.getCase not implemented");
    }
}
