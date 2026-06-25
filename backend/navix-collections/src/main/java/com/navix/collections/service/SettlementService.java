package com.navix.collections.service;

import com.navix.collections.dto.CollectionsDtos.SettlementView;
import com.navix.collections.entity.Settlement;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Maker-checker workflow for partial settlements: a collections officer PROPOSES
 * and the Collections Head APPROVES. Proposer and approver must be different
 * staff (separation of duties). The acting identity is read from
 * {@link ActorContext} as the real {@code staff_user.id} (a bigint) and stored
 * directly; views resolve those ids to names via {@link StaffDirectory}.
 *
 * <p>All amounts are integer paise.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final CollectionCaseRepository caseRepository;
    private final StaffDirectory staffDirectory;

    /** The acting staff id (a real bigint) from the current actor. */
    private static long actorStaffId() {
        try {
            return Long.parseLong(ActorContext.get().id());
        } catch (NumberFormatException e) {
            throw new BusinessException("ACTOR_NOT_STAFF",
                    "The acting identity is not a real staff id; sign in as staff");
        }
    }

    /**
     * Officer proposes a partial settlement on a case. {@code proposedBy} is the
     * current actor's staff id; the settlement opens un-approved.
     *
     * @param caseId                the collection case
     * @param settlementAmountPaise the agreed settlement amount, in paise
     */
    @Transactional
    public SettlementView propose(UUID caseId, long settlementAmountPaise) {
        if (!caseRepository.existsById(caseId)) {
            throw new ResourceNotFoundException("CollectionCase", String.valueOf(caseId));
        }
        Settlement s = new Settlement();
        s.setCollectionCaseId(caseId);
        s.setSettlementAmount(settlementAmountPaise);
        s.setProposedBy(actorStaffId());
        s.setCreatedAt(Instant.now());
        return toView(settlementRepository.save(s));
    }

    /**
     * Collections Head approves a settlement. Enforces separation of duties: the
     * approver's staff id must differ from {@code proposedBy}.
     *
     * @throws BusinessException {@code SOD_VIOLATION} if the approver also proposed it
     */
    @Transactional
    public SettlementView approve(UUID settlementId) {
        Settlement s = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
        long approver = actorStaffId();
        Long proposedBy = s.getProposedBy();
        if (proposedBy != null && proposedBy == approver) {
            throw new BusinessException("SOD_VIOLATION",
                    "The approver must differ from the proposer (separation of duties)");
        }
        s.setApprovedBy(approver);
        s.setApprovedAt(Instant.now());
        return toView(settlementRepository.save(s));
    }

    @Transactional(readOnly = true)
    public Settlement getSettlement(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
    }

    /** All settlements (pending + approved), for the collections settlements worklist. */
    @Transactional(readOnly = true)
    public List<SettlementView> listAll() {
        return settlementRepository.findAll().stream().map(this::toView).toList();
    }

    private SettlementView toView(Settlement s) {
        return new SettlementView(
                s.getId(), s.getCollectionCaseId(), s.getSettlementAmount(),
                s.getProposedBy(), staffName(s.getProposedBy()),
                s.getApprovedBy(), staffName(s.getApprovedBy()),
                s.getCreatedAt(), s.getApprovedAt());
    }

    private String staffName(Long staffId) {
        if (staffId == null) {
            return null;
        }
        return staffDirectory.findStaff(staffId).map(StaffSummary::name).orElse(null);
    }
}
