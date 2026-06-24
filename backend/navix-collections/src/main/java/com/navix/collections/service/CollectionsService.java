package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.InteractionLog;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Core collections operations: opening cases for overdue loans, assigning
 * officers, and logging borrower interactions. Enforces invariants such as
 * "a PAID interaction outcome requires a proof reference". The live DPD bucket
 * is never stored here — it is computed on read by {@link DpdCalculator}.
 */
@Service
@RequiredArgsConstructor
public class CollectionsService {

    /** Outcome value that signals the borrower paid and therefore requires proof. */
    private static final String PAID_OUTCOME = "PAID";

    private final CollectionCaseRepository caseRepository;
    private final InteractionLogRepository interactionRepository;

    /**
     * Open (or fetch) the single collection case for an overdue loan. Idempotent:
     * if a case already exists for the loan it is returned unchanged.
     */
    @Transactional
    public CollectionCase openCase(UUID loanId) {
        return caseRepository.findByLoanId(loanId)
                .orElseGet(() -> {
                    CollectionCase c = new CollectionCase();
                    c.setLoanId(loanId);
                    return caseRepository.save(c);
                });
    }

    @Transactional(readOnly = true)
    public CollectionCase getCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("CollectionCase", String.valueOf(caseId)));
    }

    @Transactional(readOnly = true)
    public List<CollectionCase> listCases() {
        return caseRepository.findAll();
    }

    /** Assign a collections officer to a case. */
    @Transactional
    public CollectionCase assignOfficer(UUID caseId, UUID officerId) {
        CollectionCase c = getCase(caseId);
        c.setAssignedOfficerId(officerId);
        return caseRepository.save(c);
    }

    /**
     * Log a borrower interaction on a case. Enforces the proof rule: when the
     * outcome indicates payment ({@code PAID}) a non-blank {@code proofRef}
     * (transaction id / screenshot reference) is mandatory.
     *
     * @throws BusinessException {@code PROOF_REQUIRED} if a PAID outcome lacks proof
     */
    @Transactional
    public InteractionLog logInteraction(UUID caseId, String type, String outcome,
                                         LocalDate promiseToPayDate, String proofRef) {
        CollectionCase c = getCase(caseId);
        if (PAID_OUTCOME.equalsIgnoreCase(outcome) && (proofRef == null || proofRef.isBlank())) {
            throw new BusinessException("PROOF_REQUIRED",
                    "A PAID outcome requires a proof reference (transaction id or screenshot)");
        }
        InteractionLog log = new InteractionLog();
        log.setCollectionCaseId(c.getId());
        log.setType(type);
        log.setOutcome(outcome);
        log.setPromiseToPayDate(promiseToPayDate);
        log.setProofRef(proofRef);
        log.setLoggedAt(Instant.now());
        return interactionRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<InteractionLog> listInteractions(UUID caseId) {
        return interactionRepository.findByCollectionCaseIdOrderByLoggedAtDesc(caseId);
    }
}
