package com.navix.loan.dto;

import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;

import java.util.List;

/**
 * DTOs for the staff-facing <b>customer</b> (borrower-centric) views — a cross-application roll-up
 * keyed on the bigint {@code applicant_id}. Unlike the per-application surfaces, these aggregate all
 * of an applicant's applications, loans and payments so staff can see a borrower's whole history.
 * Identity fields (PAN/mobile) are returned in full — these are staff-only surfaces.
 */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /**
     * One row in the customers list: an applicant plus rolled-up counts and total outstanding (paise),
     * and their latest credit headline (score + 1–5★ rating) for the staff Customers dashboard.
     */
    public record CustomerSummary(
            Long applicantId,
            String name,
            String pan,
            String mobile,
            int applicationCount,
            int loanCount,
            String latestStatus,
            long totalOutstandingPaise,
            Integer creditScore,
            Double starRating) {
    }

    /** Full borrower history: latest KYC profile + every application, loan and payment (newest first). */
    public record CustomerDetail(
            Long applicantId,
            ProfileView profile,
            List<ApplicationView> applications,
            List<LoanView> loans,
            List<PaymentView> payments) {
    }

    /**
     * Admin edit of a customer's KYC data. Identity fields (PAN/Aadhaar/mobile) are intentionally
     * <b>not</b> editable here — they carry uniqueness constraints and stay locked.
     */
    public record UpdateCustomerRequest(
            String fullName,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            String salaryBank) {
    }
}
