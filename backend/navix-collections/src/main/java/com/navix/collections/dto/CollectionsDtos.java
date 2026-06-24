package com.navix.collections.dto;

import com.navix.collections.domain.DpdBucket;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.InteractionLog;
import com.navix.collections.entity.Settlement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request/response DTOs for the collections module. All monetary fields are
 * <b>integer paise</b>. Views are mapped from entities via the static
 * {@code of(...)} factories. Mirrors {@code LoanDtos}.
 */
public final class CollectionsDtos {

    private CollectionsDtos() {
        // container for nested DTO records
    }

    /** Open a collection case for an overdue loan. */
    public record OpenCaseRequest(@NotNull UUID loanId) {
    }

    /** Assign a collections officer to a case. */
    public record AssignOfficerRequest(@NotNull UUID officerId) {
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

    public record CaseView(
            UUID id,
            UUID loanId,
            String currentBucket,
            UUID assignedOfficerId,
            Instant createdAt) {

        public static CaseView of(CollectionCase c) {
            return new CaseView(c.getId(), c.getLoanId(), c.getCurrentBucket(),
                    c.getAssignedOfficerId(), c.getCreatedAt());
        }
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

    public record SettlementView(
            UUID id,
            UUID collectionCaseId,
            Long settlementAmountPaise,
            UUID proposedBy,
            UUID approvedBy,
            Instant createdAt,
            Instant approvedAt) {

        public static SettlementView of(Settlement s) {
            return new SettlementView(s.getId(), s.getCollectionCaseId(), s.getSettlementAmount(),
                    s.getProposedBy(), s.getApprovedBy(), s.getCreatedAt(), s.getApprovedAt());
        }
    }

    /** Live DPD helper result: days-past-due plus the derived bucket. */
    public record DpdView(LocalDate dueDate, LocalDate asOf, int dpd, DpdBucket bucket) {
    }
}
