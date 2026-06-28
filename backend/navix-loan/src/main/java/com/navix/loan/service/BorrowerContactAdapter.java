package com.navix.loan.service;

import com.navix.common.loan.BorrowerContactDirectory;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.RecipientType;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan-module implementation of the {@link BorrowerContactDirectory} port: resolves a borrower's
 * latest KYC profile (name + mobile + email) for the notification engine's {@code TO_BORROWER} policy.
 * Reuses {@link ApplicantReviewService#latestProfile} so reborrow applicants (no own profile) still
 * resolve via their most recent application.
 */
@Component
public class BorrowerContactAdapter implements BorrowerContactDirectory {

    private final ApplicantReviewService reviewService;

    public BorrowerContactAdapter(ApplicantReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContactInfo> borrowerContact(Long applicantId) {
        if (applicantId == null) {
            return Optional.empty();
        }
        return reviewService.latestProfile(applicantId)
                .map(p -> new ContactInfo(RecipientType.BORROWER, applicantId, p.getFullName(),
                        p.getEmail(), p.getMobile(), "BORROWER"));
    }
}
