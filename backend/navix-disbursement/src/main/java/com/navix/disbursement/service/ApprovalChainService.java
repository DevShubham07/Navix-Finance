package com.navix.disbursement.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.disbursement.domain.DisbursementStatus;
import com.navix.disbursement.entity.ApprovalStep;
import com.navix.disbursement.entity.DisbursementRequest;
import com.navix.disbursement.repository.ApprovalStepRepository;
import com.navix.disbursement.repository.DisbursementRequestRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-enforced maker-checker state machine over a {@link DisbursementRequest}:
 *
 * <pre>
 *   PENDING_CREDIT_REVIEW --recommend--> CREDIT_RECOMMENDED --approveCredit--> CREDIT_APPROVED
 *       --authoriseRelease--> RELEASE_AUTHORISED --confirmTransfer--> TRANSFER_CONFIRMED / TRANSFER_FAILED
 * </pre>
 *
 * <p>Every transition: (a) loads the request (404 if missing), (b) validates the current status
 * permits the move else {@code ILLEGAL_TRANSITION}, (c) enforces separation-of-duties — the acting
 * actor must differ from the actor who performed the prior conflicting step
 * (recommender ≠ credit-approver ≠ releaser ≠ confirmer) else {@code SOD_VIOLATION},
 * (d) appends an {@link ApprovalStep} to the audit trail, and (e) saves the new status.
 *
 * <p>The acting identity comes from the demo {@link ActorContext}; the UUID actor column is derived
 * from {@link CurrentActor#id()} (no real authentication in demo mode).
 */
@Service
@RequiredArgsConstructor
public class ApprovalChainService {

    /** Decision strings recorded on each {@link ApprovalStep}; also used to locate prior SoD steps. */
    static final String RECOMMENDED = "RECOMMENDED";
    static final String APPROVED = "APPROVED";
    static final String RELEASED = "RELEASED";
    static final String CONFIRMED = "CONFIRMED";
    static final String FAILED = "FAILED";

    private final DisbursementRequestRepository requestRepository;
    private final ApprovalStepRepository stepRepository;
    private final PennyDropGate pennyDropGate;

    /** Open a fresh disbursement request for a loan in {@code PENDING_CREDIT_REVIEW}. */
    @Transactional
    public DisbursementRequest createRequest(UUID loanId) {
        if (loanId == null) {
            throw new BusinessException("LOAN_ID_REQUIRED", "loanId is required");
        }
        DisbursementRequest request = new DisbursementRequest();
        request.setLoanId(loanId);
        request.setStatus(DisbursementStatus.PENDING_CREDIT_REVIEW);
        return requestRepository.save(request);
    }

    /** Credit Executive recommends: {@code PENDING_CREDIT_REVIEW -> CREDIT_RECOMMENDED} (first decision, no SoD). */
    @Transactional
    public DisbursementRequest recommend(UUID requestId) {
        DisbursementRequest request = require(requestId);
        expect(request, DisbursementStatus.PENDING_CREDIT_REVIEW, "recommend");
        CurrentActor actor = ActorContext.get();
        appendStep(request, actor, RECOMMENDED);
        return transition(request, DisbursementStatus.CREDIT_RECOMMENDED);
    }

    /** Credit Head approves: {@code CREDIT_RECOMMENDED -> CREDIT_APPROVED}. SoD: approver ≠ recommender. */
    @Transactional
    public DisbursementRequest approveCredit(UUID requestId) {
        DisbursementRequest request = require(requestId);
        expect(request, DisbursementStatus.CREDIT_RECOMMENDED, "approveCredit");
        CurrentActor actor = ActorContext.get();
        UUID acting = actorUuid(actor);
        enforceSoD(request, RECOMMENDED, acting, "credit approver must differ from the recommender");
        appendStep(request, actor, APPROVED);
        return transition(request, DisbursementStatus.CREDIT_APPROVED);
    }

    /**
     * Disbursement Head authorises release: {@code CREDIT_APPROVED -> RELEASE_AUTHORISED}.
     * SoD: releaser ≠ credit approver. Penny-drop verification must pass first.
     */
    @Transactional
    public DisbursementRequest authoriseRelease(UUID requestId) {
        DisbursementRequest request = require(requestId);
        expect(request, DisbursementStatus.CREDIT_APPROVED, "authoriseRelease");
        if (!pennyDropGate.passed(requestId)) {
            throw new BusinessException("PENNY_DROP_REQUIRED",
                    "Penny-drop verification must pass before release can be authorised");
        }
        CurrentActor actor = ActorContext.get();
        UUID acting = actorUuid(actor);
        enforceSoD(request, APPROVED, acting, "releaser must differ from the credit approver");
        appendStep(request, actor, RELEASED);
        return transition(request, DisbursementStatus.RELEASE_AUTHORISED);
    }

    /**
     * Accountant confirms the manual bank transfer: {@code RELEASE_AUTHORISED -> TRANSFER_CONFIRMED}
     * on success, or {@code -> TRANSFER_FAILED} on failure. SoD: confirmer ≠ releaser.
     */
    @Transactional
    public DisbursementRequest confirmTransfer(UUID requestId, boolean success) {
        DisbursementRequest request = require(requestId);
        expect(request, DisbursementStatus.RELEASE_AUTHORISED, "confirmTransfer");
        CurrentActor actor = ActorContext.get();
        UUID acting = actorUuid(actor);
        enforceSoD(request, RELEASED, acting, "transfer confirmer must differ from the releaser");
        appendStep(request, actor, success ? CONFIRMED : FAILED);
        return transition(request,
                success ? DisbursementStatus.TRANSFER_CONFIRMED : DisbursementStatus.TRANSFER_FAILED);
    }

    @Transactional(readOnly = true)
    public DisbursementRequest getRequest(UUID requestId) {
        return require(requestId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> getSteps(UUID requestId) {
        require(requestId);
        return stepRepository.findByDisbursementRequestIdOrderByAtAsc(requestId);
    }

    // --- internals -------------------------------------------------------------------------

    private DisbursementRequest require(UUID requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("DisbursementRequest",
                        String.valueOf(requestId)));
    }

    private void expect(DisbursementRequest request, DisbursementStatus required, String action) {
        if (request.getStatus() != required) {
            throw new BusinessException("ILLEGAL_TRANSITION",
                    "Cannot %s a request in status %s (expected %s)"
                            .formatted(action, request.getStatus(), required));
        }
    }

    /** Reject if {@code acting} already performed the prior step identified by {@code priorDecision}. */
    private void enforceSoD(DisbursementRequest request, String priorDecision, UUID acting, String why) {
        UUID priorActor = priorActorId(request.getId(), priorDecision);
        if (priorActor != null && priorActor.equals(acting)) {
            throw new BusinessException("SOD_VIOLATION",
                    "Separation of duties violated: " + why);
        }
    }

    /** actorId of the most recent step with the given decision on this request, or null if none. */
    private UUID priorActorId(UUID requestId, String decision) {
        List<ApprovalStep> steps = stepRepository.findByDisbursementRequestIdOrderByAtAsc(requestId);
        UUID found = null;
        for (ApprovalStep step : steps) {
            if (decision.equals(step.getDecision())) {
                found = step.getActorId();
            }
        }
        return found;
    }

    private void appendStep(DisbursementRequest request, CurrentActor actor, String decision) {
        ApprovalStep step = new ApprovalStep();
        step.setDisbursementRequestId(request.getId());
        step.setRole(actor.role());
        step.setActorId(actorUuid(actor));
        step.setDecision(decision);
        step.setAt(Instant.now());
        stepRepository.save(step);
    }

    private DisbursementRequest transition(DisbursementRequest request, DisbursementStatus to) {
        request.setStatus(to);
        request.setUpdatedAt(Instant.now());
        return requestRepository.save(request);
    }

    /** Derive a stable UUID for the maker-checker actor column from the demo actor id. */
    private static UUID actorUuid(CurrentActor actor) {
        return UUID.nameUUIDFromBytes(actor.id().getBytes(StandardCharsets.UTF_8));
    }
}
