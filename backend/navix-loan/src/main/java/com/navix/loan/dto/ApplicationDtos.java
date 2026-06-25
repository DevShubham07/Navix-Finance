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
     * {@code approvedAmountPaise} is used only by the Credit Head's final approval;
     * {@code txnRef} is the disbursal transaction id — when the Disbursement Head supplies it the
     * release skips the accountant gate (also recorded by the accountant on confirmation).
     */
    public record DecisionRequest(boolean decision, Long approvedAmountPaise, String notes, String txnRef) {
    }

    public record ApplicationView(
            Long id,
            Long applicantId,
            ApplicationStatus status,
            Long amountRequestedPaise,
            Long eligibleLimitPaise,
            String purpose,
            Long assignedExecutiveId,
            Long loanId,
            boolean fastTrack) {

        public static ApplicationView of(LoanApplication a) {
            // A pre-approved reborrow reaches disbursement without a credit executive being assigned;
            // the Disbursement Head surfaces these in a separate fast-track section.
            boolean fastTrack = a.getStatus() == ApplicationStatus.DISBURSEMENT_PENDING
                    && a.getAssignedExecutiveId() == null;
            return new ApplicationView(a.getId(), a.getApplicantId(), a.getStatus(),
                    a.getAmountRequested(), a.getEligibleLimit(), a.getPurpose(),
                    a.getAssignedExecutiveId(), a.getLoanId(), fastTrack);
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
