package com.navix.collections.service;

import com.navix.collections.dto.CollectionsDtos.CaseDetailView;
import com.navix.collections.dto.CollectionsDtos.CaseView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.InteractionLog;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.event.CollectionCaseOpenedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core collections operations: opening cases for collectible loans, assigning
 * officers, and logging borrower interactions. Cases are bridged to the real
 * loans via {@link LoanDirectory} (loan + borrower detail) and to real staff via
 * {@link StaffDirectory} (officer names + activation gating). The live DPD bucket
 * is never stored — it is computed on read by {@link DpdCalculator}.
 */
@Service
@RequiredArgsConstructor
public class CollectionsService {

    /** Outcome value that signals the borrower paid and therefore requires proof. */
    private static final String PAID_OUTCOME = "PAID";

    /** Role an assigned collections officer must hold (and be ACTIVE in). */
    private static final String OFFICER_ROLE = "COLLECTION_EXECUTIVE";

    /** Role allowed to *assign* a case to an officer (collections management). */
    private static final String MANAGER_ROLE = "COLLECTION_HEAD";

    /** Loan statuses that mean the loan is settled — its case drops off the collections worklist. */
    private static final Set<String> TERMINAL_LOAN_STATUSES = Set.of("CLOSED", "REPAID", "WRITTEN_OFF");

    private final CollectionCaseRepository caseRepository;
    private final InteractionLogRepository interactionRepository;
    private final LoanDirectory loanDirectory;
    private final StaffDirectory staffDirectory;
    private final DpdCalculator dpdCalculator;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Open (or fetch) the single collection case for a real loan, and move that
     * loan into collections. Idempotent: an existing case for the loan is returned
     * unchanged. The loan must resolve via {@link LoanDirectory}.
     *
     * @throws ResourceNotFoundException if no such loan exists
     */
    @Transactional
    public CaseDetailView openCase(Long loanId) {
        LoanSummary loan = loanDirectory.findLoan(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
        Optional<CollectionCase> existing = caseRepository.findByLoanId(loanId);
        CollectionCase c = existing.orElseGet(() -> {
            CollectionCase nc = new CollectionCase();
            nc.setLoanId(loanId);
            return caseRepository.save(nc);
        });
        loanDirectory.markInCollections(loanId);
        if (existing.isEmpty()) {
            // Only a freshly-opened case fires the notification (an idempotent re-open is silent).
            eventPublisher.publishEvent(new CollectionCaseOpenedEvent(
                    c.getId(), loanId, loan.customerId(), Instant.now()));
        }
        return buildDetail(c);
    }

    /** Full case detail (loan figures + borrower + live DPD + officer name). */
    @Transactional(readOnly = true)
    public CaseDetailView getCaseDetail(UUID caseId) {
        return buildDetail(getCase(caseId));
    }

    /**
     * The collections worklist as enriched rows. Cases whose loan has settled (CLOSED/REPAID/
     * WRITTEN_OFF) are dropped so a fully-repaid loan no longer surfaces as an open case.
     */
    @Transactional(readOnly = true)
    public List<CaseView> listCaseViews() {
        return caseRepository.findAll().stream()
                .map(this::toListView)
                .filter(v -> v.loanStatus() == null || !TERMINAL_LOAN_STATUSES.contains(v.loanStatus()))
                .toList();
    }

    /** Loans eligible to open a case against (ACTIVE/OVERDUE, due on or before {@code asOf}). */
    @Transactional(readOnly = true)
    public List<LoanSummary> collectibleLoans(LocalDate asOf) {
        return loanDirectory.listCollectible(asOf);
    }

    /** ACTIVE collections officers, for the assignee picker (activation gating). */
    @Transactional(readOnly = true)
    public List<StaffSummary> assignableOfficers() {
        return staffDirectory.listActive(OFFICER_ROLE);
    }

    /** Load the case entity (used internally and by interaction logging). */
    @Transactional(readOnly = true)
    public CollectionCase getCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("CollectionCase", String.valueOf(caseId)));
    }

    /**
     * Assign a collections officer to a case. Assigning is a **collections-management** action:
     * only a {@code COLLECTION_HEAD} (or ADMIN) may do it — a Collection Executive can log
     * interactions and propose settlements, but not assign cases. The officer being assigned must
     * itself be an ACTIVE {@code COLLECTION_EXECUTIVE} (activation gating).
     *
     * @throws BusinessException {@code FORBIDDEN_ROLE} if the actor is not a Collection Head/ADMIN
     * @throws BusinessException {@code INVALID_OFFICER} if the officer is not an active executive
     */
    @Transactional
    public CollectionCase assignOfficer(UUID caseId, Long officerId) {
        CurrentActor actor = ActorContext.get();
        String actorRole = actor != null ? actor.role() : null;
        if (!MANAGER_ROLE.equals(actorRole) && !"ADMIN".equals(actorRole)) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    "Only the Collection Head can assign a case to an officer");
        }
        CollectionCase c = getCase(caseId);
        if (!staffDirectory.isActiveWithRole(officerId, OFFICER_ROLE)) {
            throw new BusinessException("INVALID_OFFICER",
                    "The assignee must be an ACTIVE collections executive");
        }
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

    // --- view builders -----------------------------------------------------

    private CaseDetailView buildDetail(CollectionCase c) {
        LoanSummary loan = loanDirectory.findLoan(c.getLoanId()).orElse(null);
        int dpd = dpd(loan);
        return new CaseDetailView(c.getId(), c.getLoanId(), c.getAssignedOfficerId(),
                officerName(c.getAssignedOfficerId()), c.getCreatedAt(),
                dpd, dpdCalculator.bucket(dpd), loan);
    }

    private CaseView toListView(CollectionCase c) {
        LoanSummary loan = loanDirectory.findLoan(c.getLoanId()).orElse(null);
        int dpd = dpd(loan);
        return new CaseView(c.getId(), c.getLoanId(), c.getAssignedOfficerId(),
                officerName(c.getAssignedOfficerId()), c.getCreatedAt(),
                dpd, dpdCalculator.bucket(dpd),
                loan != null ? loan.status() : null,
                loan != null ? loan.borrowerName() : null,
                loan != null ? loan.outstandingPaise() : null,
                loan != null ? loan.dueDate() : null);
    }

    private int dpd(LoanSummary loan) {
        if (loan == null || loan.dueDate() == null) {
            return 0;
        }
        return dpdCalculator.daysPastDue(loan.dueDate(), LocalDate.now());
    }

    private String officerName(Long officerId) {
        if (officerId == null) {
            return null;
        }
        return staffDirectory.findStaff(officerId).map(StaffSummary::name).orElse(null);
    }
}
