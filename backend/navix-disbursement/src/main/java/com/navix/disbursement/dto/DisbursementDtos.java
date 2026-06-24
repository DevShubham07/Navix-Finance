package com.navix.disbursement.dto;

import com.navix.disbursement.domain.DisbursementStatus;
import com.navix.disbursement.entity.ApprovalStep;
import com.navix.disbursement.entity.DisbursementRequest;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Request/response DTOs for the disbursement maker-checker module. The disbursement chain carries
 * no money fields (the loan owns the economics); these views expose the request's position in the
 * state machine and the separation-of-duties approval trail. Views map from entities via the
 * static {@code of(...)} factories.
 */
public final class DisbursementDtos {

    private DisbursementDtos() {
        // container for nested DTO records
    }

    /** Open a disbursement request for an approved loan. */
    public record CreateRequest(@NotNull UUID loanId) {
    }

    /** Read-model of a disbursement request and where it sits in the chain. */
    public record RequestView(
            UUID id,
            UUID loanId,
            DisbursementStatus status,
            Instant createdAt,
            Instant updatedAt) {

        public static RequestView of(DisbursementRequest r) {
            return new RequestView(r.getId(), r.getLoanId(), r.getStatus(),
                    r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    /** One entry in the maker-checker audit trail: who, in which role, decided what, and when. */
    public record StepView(
            UUID id,
            UUID disbursementRequestId,
            String role,
            UUID actorId,
            String decision,
            Instant at) {

        public static StepView of(ApprovalStep s) {
            return new StepView(s.getId(), s.getDisbursementRequestId(), s.getRole(),
                    s.getActorId(), s.getDecision(), s.getAt());
        }
    }
}
