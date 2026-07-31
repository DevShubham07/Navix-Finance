package com.navix.loan.dto;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;

/** Request/response DTOs for the application lifecycle (dfd.md §8). Money is integer paise. */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    public record CreateApplicationRequest(@NotNull Long customerId) {
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
            Long customerId,
            ApplicationStatus status,
            Long amountRequestedPaise,
            Long eligibleLimitPaise,
            String purpose,
            Long assignedExecutiveId,
            Long loanId,
            Integer salaryCreditDay,
            boolean fastTrack,
            Integer creditScore,
            Double starRating,
            String recommendation,
            String customerName,
            String customerMobile,
            String loanStatus,
            LocalDate loanDueDate) {

        public static ApplicationView of(LoanApplication a) {
            return of(a, null, null);
        }

        public static ApplicationView of(LoanApplication a, CustomerProfile p) {
            return of(a, p, null);
        }

        /**
         * Enriched with the customer's staff-only credit headline (score + 1–5★ + verdict), name and
         * mobile, so every staff queue row identifies the human on it — plus, once a loan exists, its
         * <b>effective</b> status and due date.
         *
         * <p>The loan fields matter because {@code LoanStatus.OVERDUE} is <b>compute-on-read</b>: no
         * code path ever writes it, and the aggregate's own {@code loan_application.status} stays
         * ACTIVE for the whole repayment window. A queue that split on the application status alone
         * would therefore show every past-due loan as "active" and an always-empty overdue column.
         * {@code loanDueDate} is the authoritative signal (DPD is derived from it everywhere in this
         * product), and it stays correct for a loan already flipped to IN_COLLECTIONS, which
         * {@code effectiveStatus} does not promote.
         *
         * <p>{@code p} / {@code loan} may be null (no profile, no loan yet, or the borrower-facing
         * path) — the corresponding fields then stay {@code null}, never leaking customer PII to the
         * borrower-facing {@link #of(LoanApplication)} overload.
         */
        public static ApplicationView of(LoanApplication a, CustomerProfile p, Loan loan) {
            // A pre-approved reborrow reaches disbursement without a credit executive being assigned;
            // the Disbursement Head surfaces these in a separate fast-track section.
            boolean fastTrack = a.getStatus() == ApplicationStatus.DISBURSEMENT_PENDING
                    && a.getAssignedExecutiveId() == null;
            return new ApplicationView(a.getId(), a.getCustomerId(), a.getStatus(),
                    a.getAmountRequested(), a.getEligibleLimit(), a.getPurpose(),
                    a.getAssignedExecutiveId(), a.getLoanId(), a.getSalaryCreditDay(), fastTrack,
                    p != null && p.getBureauScore() != null ? p.getBureauScore().intValue() : null,
                    p != null && p.getCreditStarRating() != null ? p.getCreditStarRating().doubleValue() : null,
                    p != null ? p.getCreditRecommendation() : null,
                    p != null ? p.getFullName() : null,
                    p != null ? p.getMobile() : null,
                    loan != null ? loan.effectiveStatus(LocalDate.now()).name() : null,
                    loan != null ? loan.getDueDate() : null);
        }
    }

    public record EventView(
            Long id,
            ApplicationStatus fromStatus,
            ApplicationStatus toStatus,
            String actorId,
            String actorRole,
            String actorName,
            String action,
            String notes,
            Instant at) {

        public static EventView of(ApplicationEvent e) {
            return of(e, null);
        }

        /**
         * Enriched with the resolved actor's display name ({@code actorName}) so the audit trail can
         * show <em>who</em> did each step, not just the role. May be {@code null} when the actor can't
         * be resolved (unknown staff id, no profile, non-numeric id) — follows the collections
         * {@code SettlementView.proposedByName} precedent.
         */
        public static EventView of(ApplicationEvent e, String actorName) {
            return new EventView(e.getId(), e.getFromStatus(), e.getToStatus(), e.getActorId(),
                    e.getActorRole(), actorName, e.getAction(), e.getNotes(), e.getAt());
        }
    }
}
