package com.navix.collections.service;

import com.navix.collections.entity.Settlement;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Maker-checker workflow for partial settlements: a collections officer PROPOSES
 * and the Collections Head APPROVES. Proposer and approver must be different
 * actors (separation of duties). The acting identity is read from
 * {@link ActorContext} and reduced to a stable UUID for the audit columns.
 *
 * <p>All amounts are integer paise.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final CollectionCaseRepository caseRepository;

    /** Derive a stable UUID for an actor id (demo identity has no native UUID). */
    private static UUID actorUuid(CurrentActor actor) {
        return UUID.nameUUIDFromBytes(actor.id().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Officer proposes a partial settlement on a case. {@code proposedBy} is the
     * derived UUID of the current actor; the settlement opens un-approved.
     *
     * @param caseId                the collection case
     * @param settlementAmountPaise the agreed settlement amount, in paise
     */
    @Transactional
    public Settlement propose(UUID caseId, long settlementAmountPaise) {
        if (!caseRepository.existsById(caseId)) {
            throw new ResourceNotFoundException("CollectionCase", String.valueOf(caseId));
        }
        Settlement s = new Settlement();
        s.setCollectionCaseId(caseId);
        s.setSettlementAmount(settlementAmountPaise);
        s.setProposedBy(actorUuid(ActorContext.get()));
        s.setCreatedAt(Instant.now());
        return settlementRepository.save(s);
    }

    /**
     * Collections Head approves a settlement. Enforces separation of duties: the
     * approver's derived UUID must differ from {@code proposedBy}.
     *
     * @throws BusinessException {@code SOD_VIOLATION} if the approver also proposed it
     */
    @Transactional
    public Settlement approve(UUID settlementId) {
        Settlement s = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
        UUID approver = actorUuid(ActorContext.get());
        if (approver.equals(s.getProposedBy())) {
            throw new BusinessException("SOD_VIOLATION",
                    "The approver must differ from the proposer (separation of duties)");
        }
        s.setApprovedBy(approver);
        s.setApprovedAt(Instant.now());
        return settlementRepository.save(s);
    }

    @Transactional(readOnly = true)
    public Settlement getSettlement(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
    }

    /** All settlements (pending + approved), for the collections settlements worklist. */
    @Transactional(readOnly = true)
    public List<Settlement> listAll() {
        return settlementRepository.findAll();
    }
}
