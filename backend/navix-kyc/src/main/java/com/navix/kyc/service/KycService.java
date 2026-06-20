package com.navix.kyc.service;

import com.navix.kyc.entity.KycCase;
import com.navix.kyc.repository.KycCaseRepository;
import com.navix.kyc.repository.KycCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Coordinates a borrower's KYC case and its individual checks.
 * STUB: orchestration logic to follow.
 * TODO: open a case, run PAN/AADHAAR/SELFIE/ADDRESS checks via
 *       navix-verification, aggregate results and compute overall status.
 */
@Service
@RequiredArgsConstructor
public class KycService {

    private final KycCaseRepository kycCaseRepository;
    private final KycCheckRepository kycCheckRepository;
    private final IdentityMatchService identityMatchService;
    private final DigiLockerSessionService digiLockerSessionService;

    /** Open (or fetch) the KYC case for a borrower. */
    public KycCase openCase(Long borrowerId) {
        // TODO: create a PENDING KycCase for the borrower.
        throw new UnsupportedOperationException("KycService.openCase not implemented");
    }

    /** Recompute and return the overall status of a KYC case. */
    public KycCase evaluate(Long kycCaseId) {
        // TODO: aggregate check results into an overall status.
        throw new UnsupportedOperationException("KycService.evaluate not implemented");
    }
}
