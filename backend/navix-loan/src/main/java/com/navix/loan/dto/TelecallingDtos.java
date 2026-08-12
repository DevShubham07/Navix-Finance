package com.navix.loan.dto;

import com.navix.loan.domain.ApplicationStatus;

/**
 * Rows for the telecaller queue (work item 10) — every application whose status has not yet reached
 * {@code SANCTIONED}, enriched for a follow-up call. Money is not relevant here (no amount is
 * sanctioned yet). Completeness reuses {@link com.navix.loan.service.AdminApplicationService}'s
 * onboarding-completeness computation (required-step count).
 */
public final class TelecallingDtos {

    private TelecallingDtos() {
    }

    /**
     * @param ownerStaffId the telecaller who owns this customer (via {@code customer_owner}), or
     *                      {@code null} when unallocated
     * @param staleDays     days since the latest {@code application_event.at} for this application
     *                      (0 for the rare row with no event history at all — {@code LoanApplication}
     *                      itself carries no creation timestamp to fall back to)
     */
    public record TelecallingView(
            Long id,
            Long customerId,
            ApplicationStatus status,
            String customerName,
            String mobile,
            String email,
            String pan,
            int stepsCompleted,
            int stepsRequired,
            Long ownerStaffId,
            long staleDays) {
    }
}
