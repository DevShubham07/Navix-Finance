package com.navix.loan.dto;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.DocumentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.CustomerCallLog;
import com.navix.loan.entity.CustomerRemark;
import com.navix.loan.entity.ProfileChangeLog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs for the staff-facing <b>customer</b> (borrower-centric) views — a cross-application roll-up
 * keyed on the bigint {@code customer_id}. Unlike the per-application surfaces, these aggregate all
 * of an customer's applications, loans and payments so staff can see a borrower's whole history.
 * Identity fields (PAN/mobile) are returned in full — these are staff-only surfaces.
 */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /**
     * One application's worth of documents, for the grouped customer-documents view (work item 4:
     * documents survive a reborrow — every customer-first entry point used to pin to the newest
     * application only, so a prior application's uploads became unreachable).
     */
    public record ApplicationDocumentGroup(
            Long applicationId,
            ApplicationStatus applicationStatus,
            List<DocumentView> documents) {
    }

    /**
     * One row in the customers list: an customer plus rolled-up counts and total outstanding (paise),
     * their latest credit headline, effective loan status (for client-side segmenting), and owner.
     */
    public record CustomerSummary(
            Long customerId,
            String name,
            String pan,
            String mobile,
            int applicationCount,
            int loanCount,
            String latestStatus,
            long totalOutstandingPaise,
            Integer creditScore,
            Double starRating,
            /** Newest loan's {@code effectiveStatus(today)} — outranks {@code latestStatus} for segments. */
            String loanStatus,
            Long ownerStaffId,
            String ownerName,
            BureauState bureauState,
            /** The customer's most recent application's {@code created_at} (V53) — the same timestamp
             *  shown as the "Date" column on the live-applications queues, surfaced here too. */
            Instant createdAt,
            // --- Parity with the live-applications queue -------------------------------------
            // A customer may hold several applications, so every field below is read off their
            // LATEST application/loan — the same one that produces `latestStatus` and `createdAt`,
            // so the whole row describes one consistent file rather than a mix of several.
            Long latestApplicationId,
            /** Where THIS advance is paid — the disbursal account, falling back to the salary
             *  account when the borrower has not reached the disbursal-account step yet. */
            String accountNumber,
            String ifsc,
            Long latestLoanId,
            /** Requested amount when the borrower has drawn one, else their eligible limit. */
            Long amountPaise,
            /** True when {@code amountPaise} is a real request, false when it is the limit — drives
             *  the "req"/"elig" tag, mirroring the live-applications Amount cell. */
            boolean amountIsRequested,
            /** Newest loan's due date. DPD is deliberately NOT sent — it is derived from this
             *  date on the client everywhere in the codebase, and a second source would drift. */
            LocalDate loanDueDate,
            Instant markedPendingAt) {
    }

    /** Full borrower history: latest KYC profile + every application, loan and payment (newest first). */
    public record CustomerDetail(
            Long customerId,
            ProfileView profile,
            List<ApplicationView> applications,
            List<LoanView> loans,
            List<PaymentView> payments,
            Long ownerStaffId,
            String ownerName,
            /** The latest application's full credit brief (categorized bureau facts + PDF doc id) — null
             *  when no bureau pull has happened yet. Reuses {@link CreditBriefDtos.CreditBriefView} as-is
             *  so the Customer roll-up and the per-application endpoint stay in lockstep. */
            CreditBriefDtos.CreditBriefView creditBrief) {
    }

    /**
     * Admin edit of a customer's KYC data. Identity fields (PAN/Aadhaar/mobile) are intentionally
     * <b>not</b> editable here — they carry uniqueness constraints and stay locked. Salary changes
     * are audited (previous→new) and recompute the eligible limit.
     */
    public record UpdateCustomerRequest(
            String fullName,
            String address,
            String employer,
            String employmentStatus,
            Long monthlySalaryPaise,
            Long annualSalaryPaise,
            BigDecimal salaryPercentage,
            BigDecimal incrementPercentage,
            String salaryBank) {
    }

    /**
     * ADMIN correcting a customer's mobile number. Does not affect login identity (JWT minting
     * never reads this field), but IS read by forgot-password lookup and the login display name —
     * see {@code CustomerService.requestMobileChangeOtp}'s javadoc for the full picture. The server
     * enforces the same cross-customer uniqueness rule KYC intake already enforces
     * ({@code DUPLICATE_MOBILE}).
     */
    public record RequestMobileChangeRequest(@NotBlank String newMobile) {
    }

    /** Confirms a mobile change with the OTP sent to {@code newMobile} — must carry the SAME value
     *  passed to the request-OTP call, or verification legitimately fails. */
    public record ConfirmMobileChangeRequest(@NotBlank String newMobile, @NotBlank String otp) {
    }

    /**
     * ADMIN correcting the amount credit approved (sanctioned) for {@code applicationId} — the
     * specific application, not just "the customer's latest one" (a customer can hold several; the
     * server verifies this id is actually theirs, sanctioned, and not yet disbursed). The OTP is
     * sent to the customer's existing mobile on file — their own consent that the approved figure is
     * changing — and is bound to this exact application + amount, so it cannot confirm a different one.
     */
    public record RequestSanctionedAmountChangeRequest(@NotNull Long applicationId, long newAmountPaise) {
    }

    /** Confirms a sanctioned-amount change with the OTP sent to the customer's mobile on file. */
    public record ConfirmSanctionedAmountChangeRequest(
            @NotNull Long applicationId, long newAmountPaise, @NotBlank String otp) {
    }

    /** One audited profile/salary change for the customer detail history pane (Phase 2.1). */
    public record ProfileChangeView(
            Long id,
            String field,
            String oldValue,
            String newValue,
            String modifiedBy,
            Instant modifiedAt) {

        public static ProfileChangeView of(ProfileChangeLog c) {
            return new ProfileChangeView(c.getId(), c.getField(), c.getOldValue(), c.getNewValue(),
                    c.getCreatedBy(), c.getCreatedAt());
        }
    }

    /**
     * One entry in the unified customer activity timeline — merges lifecycle transitions
     * ({@code application_event}), profile/salary edits ({@code profile_change_log}), KYC re-verify
     * events, verification steps ({@code application_verification}), reference contacts
     * ({@code application_reference}), staff remarks, and call logs into a single chronological
     * feed (newest first).
     */
    public record ActivityEntry(
            String type,          // LIFECYCLE | PROFILE | REVERIFY | VERIFICATION | REFERENCE | REMARK | CALL
            Long applicationId,
            String title,         // human-readable headline
            String detail,        // secondary line (old→new, notes, from→to)
            String actor,         // actor role / who made the change
            Instant at) {
    }

    /** A staff-authored remark on a customer. */
    public record AddRemarkRequest(@NotBlank String body) {
    }

    public record RemarkView(Long id, String body, String author, Instant at) {
        public static RemarkView of(CustomerRemark r) {
            return new RemarkView(r.getId(), r.getBody(), r.getCreatedBy(), r.getCreatedAt());
        }
    }

    /** Assign (or clear) customer ownership. {@code staffId} null → unallocate. */
    public record AssignOwnerRequest(Long staffId) {
    }

    /** A staff call log on a customer. {@code loanId}, if given, must belong to the same customer. */
    public record AddCallLogRequest(
            @NotBlank String callType,
            @NotBlank String outcome,
            LocalDate callbackOn,
            String notes,
            Long loanId) {
    }

    public record CallLogView(
            Long id,
            String callType,
            String outcome,
            LocalDate callbackOn,
            String notes,
            String author,
            Instant at,
            Long loanId) {
        public static CallLogView of(CustomerCallLog c) {
            return new CallLogView(c.getId(), c.getCallType(), c.getOutcome(), c.getCallbackOn(),
                    c.getNotes(), c.getCreatedBy(), c.getCreatedAt(), c.getLoanId());
        }
    }
}
