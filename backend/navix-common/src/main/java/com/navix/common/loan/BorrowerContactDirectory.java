package com.navix.common.loan;

import com.navix.common.notification.ContactInfo;
import java.util.Optional;

/**
 * Borrower contact lookup for the notification engine — resolves an customer's latest known name +
 * mobile + email from their most recent KYC profile. Implemented by navix-loan; consumed by the
 * notification {@code AudienceResolver} for the {@code TO_BORROWER} policy.
 */
public interface BorrowerContactDirectory {

    /** Contact details for a borrower by customer id, or empty if no profile is known. */
    Optional<ContactInfo> borrowerContact(Long customerId);
}
