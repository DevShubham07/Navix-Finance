package com.navix.loan.dto;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.LoanApplication;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** Request/response DTOs for the application lifecycle (dfd.md §8). Money is integer paise. */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    public record CreateApplicationRequest(@NotNull Long applicantId) {
    }

    /**
     * Borrower applies: amount + purpose (+ the eligible limit known at apply time) and the
     * salary-credit day-of-month that drives the salary-linked due date (defaults to 1 if absent).
     */
    public record ApplyRequest(@Positive long amountPaise, String purpose, Long eligibleLimitPaise,
                               Integer salaryCreditDay) {
    }

    public record AssignRequest(@NotNull Long executiveId) {
    }

    /**
     * Generic decision for the staff steps. {@code decision} = approve / accept / success;
     * {@code approvedAmountPaise} is used only by the Credit Head's final approval.
     */
    public record DecisionRequest(boolean decision, Long approvedAmountPaise, String notes) {
    }

    public record ApplicationView(
            Long id,
            Long applicantId,
            ApplicationStatus status,
            Long amountRequestedPaise,
            Long eligibleLimitPaise,
            String purpose,
            Long assignedExecutiveId,
            Long loanId) {

        public static ApplicationView of(LoanApplication a) {
            return new ApplicationView(a.getId(), a.getApplicantId(), a.getStatus(),
                    a.getAmountRequested(), a.getEligibleLimit(), a.getPurpose(),
                    a.getAssignedExecutiveId(), a.getLoanId());
        }
    }

    public record EventView(
            Long id,
            ApplicationStatus fromStatus,
            ApplicationStatus toStatus,
            String actorId,
            String actorRole,
            String action,
            String notes,
            Instant at) {

        public static EventView of(ApplicationEvent e) {
            return new EventView(e.getId(), e.getFromStatus(), e.getToStatus(), e.getActorId(),
                    e.getActorRole(), e.getAction(), e.getNotes(), e.getAt());
        }
    }
}
