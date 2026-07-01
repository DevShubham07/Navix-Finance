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
 * Reuses {@link CustomerReviewService#latestProfile} so reborrow customers (no own profile) still
 * resolve via their most recent application.
 */
@Component
public class BorrowerContactAdapter implements BorrowerContactDirectory {

    private final CustomerReviewService reviewService;

    public BorrowerContactAdapter(CustomerReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContactInfo> borrowerContact(Long customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return reviewService.latestProfile(customerId)
                .map(p -> new ContactInfo(RecipientType.BORROWER, customerId, p.getFullName(),
                        p.getEmail(), p.getMobile(), "BORROWER"));
    }
}
