package com.navix.loan.dto;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import java.time.Instant;
import java.time.LocalDate;

/**
 * ADMIN-only flat view over every application — complete AND incomplete (DRAFT / partially filled) —
 * joining the lifecycle row with its KYC snapshot and an onboarding-completeness summary. Backs the
 * admin "All applications" register and its CSV / PDF export. Money is integer paise.
 */
public final class AdminApplicationDtos {

    private AdminApplicationDtos() {
    }

    public record AdminApplicationView(
            // --- application ---
            Long id,
            Long customerId,
            ApplicationStatus status,
            Long amountRequestedPaise,
            Long eligibleLimitPaise,
            String purpose,
            Integer salaryCreditDay,
            Long assignedExecutiveId,
            Long loanId,
            // --- KYC profile (null when no profile captured yet) ---
            boolean hasProfile,
            String fullName,
            String pan,
            String aadhaar,
            String mobile,
            String email,
            LocalDate dob,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank,
            Integer creditScore,
            Double starRating,
            String recommendation,
            String riskCategory,
            // --- onboarding completeness ---
            int stepsCompleted,
            int stepsRequired,
            boolean agreementAccepted,
            boolean complete,
            Instant kycCapturedAt) {

        public static AdminApplicationView of(LoanApplication a, CustomerProfile p,
                int stepsCompleted, int stepsRequired, boolean agreementAccepted, boolean complete) {
            return new AdminApplicationView(
                    a.getId(), a.getCustomerId(), a.getStatus(),
                    a.getAmountRequested(), a.getEligibleLimit(), a.getPurpose(),
                    a.getSalaryCreditDay(), a.getAssignedExecutiveId(), a.getLoanId(),
                    p != null,
                    p != null ? p.getFullName() : null,
                    p != null ? p.getPan() : null,
                    p != null ? p.getAadhaar() : null,
                    p != null ? p.getMobile() : null,
                    p != null ? p.getEmail() : null,
                    p != null ? p.getDob() : null,
                    p != null ? p.getAddress() : null,
                    p != null ? p.getEmployer() : null,
                    p != null ? p.getEmploymentStatus() : null,
                    p != null ? p.getMonthlySalaryPaise() : null,
                    p != null ? p.getSalaryBank() : null,
                    p != null && p.getBureauScore() != null ? p.getBureauScore().intValue() : null,
                    p != null && p.getCreditStarRating() != null ? p.getCreditStarRating().doubleValue() : null,
                    p != null ? p.getCreditRecommendation() : null,
                    p != null ? p.getRiskCategory() : null,
                    stepsCompleted, stepsRequired, agreementAccepted, complete,
                    p != null ? p.getCreatedAt() : null);
        }
    }
}
