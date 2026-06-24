package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Enforces maker-checker separation of duties: across a sequence of maker-checker steps on the same
 * entity (recommend → credit-approve → release → confirm), no single actor may perform two steps.
 *
 * <p>Reusable and generic — callers in the disbursement/collections chains pass the acting staff id
 * plus the ids of whoever performed the prior steps; a repeat raises
 * {@link BusinessException} with code {@code SOD_VIOLATION} (mapped to HTTP 403 by the global handler).
 */
@Service
public class SeparationOfDutiesGuard {

    /** Stable error code for a separation-of-duties breach. */
    public static final String VIOLATION_CODE = "SOD_VIOLATION";

    /**
     * Reject if {@code actorId} already appears among the actors who performed prior maker-checker
     * steps. Null prior ids are ignored (a step not yet taken). A null {@code actorId} is itself a
     * violation — every maker-checker step must have an identifiable actor.
     *
     * @param actorId       the staff member attempting the current step
     * @param priorActorIds ids of the actors who performed earlier steps on the same entity
     * @throws BusinessException ({@code SOD_VIOLATION}) if the actor repeats a prior step
     */
    public void enforce(Long actorId, Collection<Long> priorActorIds) {
        if (actorId == null) {
            throw new BusinessException(VIOLATION_CODE,
                    "Acting staff member is required for separation-of-duties enforcement");
        }
        if (priorActorIds == null) {
            return;
        }
        boolean repeats = priorActorIds.stream()
                .filter(Objects::nonNull)
                .anyMatch(prior -> prior.equals(actorId));
        if (repeats) {
            throw new BusinessException(VIOLATION_CODE,
                    "Actor %d has already performed a conflicting maker-checker step".formatted(actorId));
        }
    }

    /** Varargs convenience over {@link #enforce(Long, Collection)}. */
    public void enforce(Long actorId, Long... priorActorIds) {
        enforce(actorId, priorActorIds == null ? null : Arrays.asList(priorActorIds));
    }

    /**
     * Verify reviewer, approver and releaser are three distinct staff members (no two steps share an
     * actor). Convenience wrapper used by the credit/disbursement chain.
     *
     * @param reviewerId Credit Executive who reviewed the application
     * @param approverId Credit Head who gave final approval (must differ from the reviewer)
     * @param releaserId Disbursement Head who released funds (must differ from reviewer and approver)
     */
    public void assertDistinctActors(Long reviewerId, Long approverId, Long releaserId) {
        // approver must not be the reviewer; releaser must not be the reviewer or the approver.
        enforce(approverId, reviewerId);
        enforce(releaserId, reviewerId, approverId);
    }
}
