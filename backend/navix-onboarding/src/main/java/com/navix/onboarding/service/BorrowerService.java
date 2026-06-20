package com.navix.onboarding.service;

import com.navix.onboarding.entity.Borrower;
import com.navix.onboarding.repository.BorrowerRepository;
import com.navix.onboarding.repository.SignupApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Borrower sign-up and profile orchestration.
 * STUB: wires repositories; flow logic to follow.
 * TODO: implement step-by-step sign-up progression, profile updates and
 *       salary/email corroboration.
 */
@Service
@RequiredArgsConstructor
public class BorrowerService {

    private final BorrowerRepository borrowerRepository;
    private final SignupApplicationRepository signupApplicationRepository;
    private final OtpService otpService;

    /** Create (or fetch) a borrower record to begin sign-up. */
    public Borrower startSignup(String mobile) {
        // TODO: create Borrower + SignupApplication, trigger mobile OTP.
        throw new UnsupportedOperationException("BorrowerService.startSignup not implemented");
    }

    /** Load a borrower's profile. */
    public Borrower getById(Long borrowerId) {
        // TODO: fetch and map borrower profile.
        throw new UnsupportedOperationException("BorrowerService.getById not implemented");
    }
}
