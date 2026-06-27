package com.navix.loan.dto;

import jakarta.validation.constraints.NotBlank;
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

    public record SalaryVerifyRequest(@Positive long monthlySalaryPaise, String slipObjectKey) {
    }

    public record PennyDropVerifyRequest(@NotBlank String accountNumber, @NotBlank String ifsc) {
    }

    public record SelfieVerifyRequest(@NotBlank String selfieObjectKey) {
    }

    public record AgreementRequest(List<String> versions) {
    }

    /** App-scoped presigned-upload request (salary slip, selfie). */
    public record PresignUploadRequest(@NotBlank String docType, String fileName, @NotBlank String contentType) {
    }
}
