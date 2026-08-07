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

    // ---- collections payments (V47) ---------------------------------------------------

    /**
     * A collections officer records what the borrower paid. {@code kind} is one of
     * {@code PART_PAYMENT} / {@code FULL_PAYMENT} / {@code SETTLEMENT}; a settlement must have an
     * open settlement on the case, named by {@code settlementId} when there is more than one.
     *
     * <p>{@code proofRef} is deliberately free text — collections proof is flexible (a screenshot
     * key, a UTR, or the officer's own note), an existing product rule.
     */
    public record RaiseCollectionPaymentRequest(
            @NotBlank String kind,
            @Positive long amountPaise,
            LocalDate paidOn,
            String txnRef,
            String proofRef,
            UUID settlementId) {
    }

    /** The Accountant's decision on a collections payment; {@code remarks} required on a reject. */
    public record ValidateCollectionPaymentRequest(@NotNull Boolean accept, String remarks) {
    }

    /**
     * A collections payment with its approval chain resolved to real staff names. {@code status} is
     * the source of truth ({@code PENDING_HEAD} → {@code PENDING_ACCOUNTANT} → {@code VALIDATED} /
     * {@code REJECTED}); {@code ledgerPaymentId} is the loan-ledger payment the validation minted,
     * and is null for anything that has not been validated.
     */
    public record CollectionPaymentView(
            UUID id,
            UUID collectionCaseId,
            Long loanId,
            String kind,
            Long amountPaise,
            LocalDate paidOn,
            String txnRef,
            String proofRef,
            UUID settlementId,
            String status,
            Long raisedBy,
            String raisedByName,
            Instant raisedAt,
            Long validatedBy,
            String validatedByName,
            Instant validatedAt,
            String remarks,
            Long ledgerPaymentId,
            String borrowerName) {
    }
}
