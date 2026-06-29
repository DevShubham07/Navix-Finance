package com.navix.loan.dto;

import java.time.Instant;

/**
 * Staff-facing credit brief view: the 1–5★ recommendation headline + the categorized bureau facts
 * (spec Categories A/B/C) + the stored {@code CREDIT_BRIEF} PDF document id (for the presigned
 * download). Never returned on a borrower-facing path.
 */
public final class CreditBriefDtos {

    private CreditBriefDtos() {
    }

    public record CreditBriefView(
            Long applicationId,
            boolean available,
            Integer creditScore,
            Double starRating,
            String recommendation,
            String summary,
            Instant generatedAt,
            Long documentId,
            Facts facts) {

        /** Categorized facts for the card. Full PAN/mobile (staff-only); amounts in rupees (the bureau's unit). */
        public record Facts(
                String name,
                String pan,
                String mobile,
                String dob,
                String city,
                String pin,
                Integer creditScore,
                Integer totalAccounts,
                Integer activeAccounts,
                Integer closedAccounts,
                Integer defaults,
                Long totalBalance,
                Long securedBalance,
                Long unsecuredBalance,
                Integer recentInquiries30d) {
        }
    }
}
