package com.navix.loan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request/response DTOs for the borrower's post-sanction offer journey (V46). Money is integer paise. */
public final class OfferDtos {

    private OfferDtos() {
    }

    /** Screen 1: how much of the sanctioned ceiling the borrower wants. */
    public record ChooseAmountRequest(@Positive long amountPaise) {
    }

    public record ReferenceInput(@NotBlank String fullName, @NotBlank String mobile,
                                 @NotBlank String relation) {
    }

    public record ReferencesRequest(List<ReferenceInput> references) {
    }

    public record ReferenceView(int slot, String fullName, String mobile, String relation) {
    }

    /**
     * Screen 5. {@code ceilingPaise} is what credit sanctioned; {@code principalPaise} is what the
     * borrower chose to draw. {@code expectedDisbursalDate} is calendar-day, 18:00 IST cut-off.
     */
    public record OfferSummaryView(
            Long applicationId,
            long ceilingPaise,
            long principalPaise,
            long processingFeePaise,
            long gstPaise,
            long netDisbursedPaise,
            long interestPaise,
            long totalRepayablePaise,
            int tenureDays,
            LocalDate repaymentDate,
            LocalDate expectedDisbursalDate) {
    }

    /** Screen 8: the stored Key Fact Statement and a short-lived URL to read it. */
    public record SanctionLetterView(Long documentId, String url) {
    }

    /**
     * Screen 11. {@code lockedUntil} non-null means the borrower has burnt their penny-drop attempts
     * and must wait; {@code attemptsLeft} is how many changed-account tries remain before that.
     */
    public record DisbursalAccountView(
            String accountNumber,
            String ifsc,
            String holderName,
            String bank,
            boolean verified,
            boolean pennyDropRequired,
            Instant lockedUntil,
            int attemptsLeft) {
    }

    public record DisbursalAccountRequest(@NotBlank String accountNumber, @NotBlank String ifsc,
                                          String holderName, String bank) {
    }
}
