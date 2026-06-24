package com.navix.onboarding.dto;

import com.navix.onboarding.domain.SignupStep;
import com.navix.onboarding.entity.Borrower;
import com.navix.onboarding.entity.SignupApplication;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request/response DTOs for the onboarding module. All monetary fields are <b>integer paise</b>.
 * Views are mapped from entities via the static {@code of(...)} factories. Mirrors LoanDtos.
 */
public final class OnboardingDtos {

    private OnboardingDtos() {
        // container for nested DTO records
    }

    /** Begin sign-up: create a borrower keyed by mobile. Other profile fields are optional. */
    public record CreateBorrowerRequest(
            @NotBlank String mobile,
            String pan,
            String personalEmail,
            String officialEmail,
            String employmentStatus,
            String uan,
            Long declaredSalaryPaise,
            String salaryBank,
            Integer salaryCreditDay) {
    }

    /** Advance the borrower's sign-up application to a given step (and optionally complete it). */
    public record AdvanceStepRequest(
            @NotNull SignupStep step,
            boolean completed) {
    }

    /** Request an OTP for a destination (mobile/email). */
    public record OtpRequest(
            @NotBlank String destination) {
    }

    /** Verify a previously issued OTP. */
    public record OtpVerifyRequest(
            @NotBlank String destination,
            @NotBlank String code) {
    }

    /** Demo OTP response — exposes the code since no real delivery channel is wired. */
    public record OtpResponse(
            String destination,
            String code,
            String message) {
    }

    /** Result of an OTP verification. */
    public record OtpVerifyResponse(
            String destination,
            boolean verified) {
    }

    public record BorrowerView(
            Long id,
            String pan,
            String mobile,
            String personalEmail,
            String officialEmail,
            String employmentStatus,
            String uan,
            Long declaredSalaryPaise,
            String salaryBank,
            Integer salaryCreditDay,
            String status) {

        public static BorrowerView of(Borrower b) {
            return new BorrowerView(b.getId(), b.getPan(), b.getMobile(), b.getPersonalEmail(),
                    b.getOfficialEmail(), b.getEmploymentStatus(), b.getUan(), b.getDeclaredSalary(),
                    b.getSalaryBank(), b.getSalaryCreditDay(), b.getStatus());
        }
    }

    public record SignupApplicationView(
            Long id,
            Long borrowerId,
            SignupStep currentStep,
            boolean completed) {

        public static SignupApplicationView of(SignupApplication a) {
            return new SignupApplicationView(a.getId(), a.getBorrowerId(), a.getCurrentStep(),
                    a.isCompleted());
        }
    }

    /** Combined view returned when a borrower is created: profile + its sign-up application. */
    public record SignupView(
            BorrowerView borrower,
            SignupApplicationView application) {

        public static SignupView of(Borrower b, SignupApplication a) {
            return new SignupView(BorrowerView.of(b), SignupApplicationView.of(a));
        }
    }
}
