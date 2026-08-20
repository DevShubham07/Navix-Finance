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
     * Screen 9, Aadhaar e-sign. The redirect URLs are built by the browser from its own origin (the same
     * convention DigiLocker uses) and carry a per-attempt nonce, so the provider cannot re-serve a stale
     * signing session.
     */
    public record EsignInitRequest(@NotBlank String successRedirectUrl,
                                   @NotBlank String failureRedirectUrl) {
    }

    /** Screen 9, fallback: a signature the borrower drew in the app. */
    public record ManualEsignRequest(@NotBlank String signatureDataUrl,
                                     Double latitude, Double longitude, Double accuracyMeters) {
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

    /**
     * {@code useBankProof} is the escape hatch for a borrower the penny drop has locked out (a
     * name-at-bank mismatch, most often): instead of another automated attempt, they upload a
     * cancelled cheque or passbook (already persisted as a {@code BANK_PROOF} document via the
     * existing presign-upload flow) and the Disbursement Head verifies it by eye before release.
     */
    public record DisbursalAccountRequest(@NotBlank String accountNumber, @NotBlank String ifsc,
                                          String holderName, String bank, boolean useBankProof) {
    }
}
