package com.navix.disbursement.service;

import com.navix.disbursement.entity.DisbursementRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Enforces the maker-checker disbursement approval chain:
 * Credit Executive (review) -> Credit Head (approve) -> Disbursement Head (release).
 * Each transition must be performed by a DIFFERENT person (separation of duties),
 * guarded by the SeparationOfDutiesGuard concept, and every action appends an
 * ApprovalStep to the audit trail.
 *
 * Business logic is intentionally stubbed for scaffolding.
 */
@Service
public class ApprovalChainService {

    /** Credit Executive recommends the request. TODO: validate state + record step. */
    public DisbursementRequest creditReview(UUID requestId, UUID actorId) {
        // TODO: PENDING_CREDIT_REVIEW -> CREDIT_RECOMMENDED; append ApprovalStep.
        throw new UnsupportedOperationException("TODO: implement creditReview");
    }

    /** Credit Head approves. TODO: enforce actor != credit executive; record step. */
    public DisbursementRequest creditHeadApprove(UUID requestId, UUID actorId) {
        // TODO: CREDIT_RECOMMENDED -> CREDIT_APPROVED; SeparationOfDutiesGuard.
        throw new UnsupportedOperationException("TODO: implement creditHeadApprove");
    }

    /** Disbursement Head authorises release. TODO: enforce distinct actor + penny-drop gate. */
    public DisbursementRequest authoriseRelease(UUID requestId, UUID actorId) {
        // TODO: CREDIT_APPROVED -> RELEASE_AUTHORISED via PennyDropGate; SeparationOfDutiesGuard.
        throw new UnsupportedOperationException("TODO: implement authoriseRelease");
    }
}
