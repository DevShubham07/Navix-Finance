package com.navix.collections.dto;

import com.navix.collections.domain.DpdBucket;
import com.navix.collections.entity.InteractionLog;
import com.navix.common.loan.LoanSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request/response DTOs for the collections module. All monetary fields are
 * <b>integer paise</b>. Case/settlement views are enriched with real loan,
 * borrower, and staff detail by the services (they need cross-module
 * collaborators), so — unlike before — they have no entity {@code of(...)} factory.
 */
public final class CollectionsDtos {

    private CollectionsDtos() {
        // container for nested DTO records
    }

    /** Open a collection case for a real (collectible) loan. */
    public record OpenCaseRequest(@NotNull Long loanId) {
    }

    /** Assign a collections officer (a real ACTIVE staff id) to a case. */
    public record AssignOfficerRequest(@NotNull Long officerId) {
    }

    /**
     * Log a borrower interaction. When {@code outcome} is {@code PAID} a non-blank
     * {@code proofRef} is required (enforced in the service).
     */
    public record LogInteractionRequest(
            @NotBlank String type,
            @NotBlank String outcome,
            LocalDate promiseToPayDate,
            String proofRef) {
    }

    /** Officer proposes a partial settlement (amount in paise). */
    public record ProposeSettlementRequest(@Positive long settlementAmountPaise) {
    }

    /**
     * A row in the collections worklist: the case plus enough real loan/borrower
     * detail to render the list. DPD and bucket are computed live from the loan's
     * due date. Loan-derived fields are null if the loan can't be resolved.
     */
    public record CaseView(
            UUID id,
            Long loanId,
            Long assignedOfficerId,
            String assignedOfficerName,
            Instant createdAt,
            int dpd,
            DpdBucket bucket,
            String loanStatus,
            String borrowerName,
            Long outstandingPaise,
            LocalDate dueDate) {
    }

    /**
     * Full case detail: the case, live DPD/bucket, the resolved officer name, and
     * the complete {@link LoanSummary} (loan figures + borrower). {@code loan} is
     * null only if the linked loan no longer resolves.
     */
    public record CaseDetailView(
            UUID id,
            Long loanId,
            Long assignedOfficerId,
            String assignedOfficerName,
            Instant createdAt,
            int dpd,
            DpdBucket bucket,
            LoanSummary loan) {
    }

    public record InteractionView(
            UUID id,
            UUID collectionCaseId,
            String type,
            String outcome,
            LocalDate promiseToPayDate,
            String proofRef,
            Instant loggedAt) {

        public static InteractionView of(InteractionLog l) {
            return new InteractionView(l.getId(), l.getCollectionCaseId(), l.getType(),
                    l.getOutcome(), l.getPromiseToPayDate(), l.getProofRef(), l.getLoggedAt());
        }
    }

    /**
     * A settlement with proposer/approver/rejecter resolved to real staff names (the bigint
     * ids are kept too) plus its maker-checker {@code status}. Built by {@code SettlementService}.
     */
    public record SettlementView(
            UUID id,
            UUID collectionCaseId,
            Long settlementAmountPaise,
            Long proposedBy,
            String proposedByName,
            Long approvedBy,
            String approvedByName,
            Long rejectedBy,
            String rejectedByName,
            String status,
            Instant createdAt,
            Instant approvedAt,
            Instant rejectedAt) {
    }

    /** Live DPD helper result: days-past-due plus the derived bucket. */
    public record DpdView(LocalDate dueDate, LocalDate asOf, int dpd, DpdBucket bucket) {
    }
}
