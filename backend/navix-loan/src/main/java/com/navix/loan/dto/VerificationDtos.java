package com.navix.loan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Request bodies for the borrower onboarding {@code /verify/*} endpoints. Each step's
 * result is the verification service's borrower-safe {@code StepResult} (never carries
 * bureau score / raw PII).
 */
public final class VerificationDtos {

    private VerificationDtos() {
    }

    public record EmailVerifyRequest(@NotBlank String officialEmail) {
    }

    /** Geo coordinates (preferred) or a typed address fallback when geolocation is denied. */
    public record AddressVerifyRequest(Double latitude, Double longitude, String manualAddress) {
    }

    public record DigilockerInitRequest(@NotBlank String redirectUrl) {
    }

    public record PanVerifyRequest(@NotBlank String pan) {
    }

    /**
     * {@code filePassword} is the optional key for password-protected payslip PDFs (V56); it is
     * applied to every slip persisted by this call.
     */
    public record SalaryVerifyRequest(@Positive long monthlySalaryPaise, List<String> slipObjectKeys,
                                      Integer salaryCreditDay, String filePassword) {
    }

    public record PennyDropVerifyRequest(@NotBlank String accountNumber, @NotBlank String ifsc) {
    }

    public record SelfieVerifyRequest(@NotBlank String selfieObjectKey) {
    }

    public record AgreementRequest(List<String> versions) {
    }

    /**
     * Credit-bureau consent, step-up verified by the borrower's mobile OTP. {@code consentText} is the
     * exact wording shown on screen, stored verbatim so the audit row proves WHAT was agreed to, not
     * just that a box was ticked. The mobile is never accepted from the client — it is resolved from
     * the stored profile.
     */
    public record BureauConsentRequest(@NotBlank String otp, @NotBlank String consentText) {
    }

    /** The code emailed to the borrower's PERSONAL address. The address itself is never accepted
     *  from the client — it is resolved from the stored profile. */
    public record EmailOtpVerifyRequest(@NotBlank String otp) {
    }

    /**
     * The bureau-consent OTP the borrower already verified (see {@link BureauConsentRequest}), forwarded
     * so it can be threaded into Digitap's Credit Analytics payload. Optional/nullable: a staff-triggered
     * manual pull (or a request made before consent exists) simply omits it, which degrades the pull to
     * {@code REVIEW} rather than proceeding without consent.
     */
    public record BureauPullRequest(String otp) {
    }

    /** App-scoped presigned-upload request (salary slip, selfie). */
    public record PresignUploadRequest(@NotBlank String docType, String fileName, @NotBlank String contentType) {
    }

    /**
     * Persist already-uploaded S3 keys as {@code ApplicationDocument} rows under an arbitrary (allow-
     * listed) docType — the generic counterpart to {@code salary(...)}'s hardcoded SALARY_SLIP
     * persistence. Used for the 6-month bank-statement upload on the bank-details page.
     */
    public record UploadedDocumentsRequest(@NotBlank String docType, @NotEmpty List<String> objectKeys,
                                          String filePassword) {
    }
}
