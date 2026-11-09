package com.navix.collections.service;

import com.navix.collections.dto.CollectionsDtos.SettlementView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanDues;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.event.SettlementApprovedEvent;
import com.navix.common.notification.event.SettlementProposedEvent;
import com.navix.common.notification.event.SettlementRejectedEvent;
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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** Role a settlement proposer must hold (a collections officer). */
    private static final String OFFICER_ROLE = "COLLECTION_EXECUTIVE";

    /** Role that approves a settlement (collections management). */
    private static final String MANAGER_ROLE = "COLLECTION_HEAD";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Stateless; DPD on the settlements register is computed on read like everywhere else. */
    private static final DpdCalculator DPD = new DpdCalculator();

    private final SettlementRepository settlementRepository;
    private final CollectionCaseRepository caseRepository;
    private final StaffDirectory staffDirectory;
    private final LoanDirectory loanDirectory;
    private final ApplicationEventPublisher eventPublisher;
    private final CollectionsService collectionsService;

    /** Authorise the current actor against one of {@code roles} (ADMIN always passes). */
    private static void requireOneOf(String... roles) {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return;
        }
        for (String r : roles) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new BusinessException("FORBIDDEN_ROLE",
                "This action requires one of: " + String.join(", ", roles));
    }

    /**
     * Readers of the settlement list: any staff role but DSA. The list carries collection case ids,
     * which open the borrower's case detail, so the DSA firewall applies here as well.
     */
    private static void requireNonDsaStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role) || "DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
    }

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
        // A collections officer (or the Head/ADMIN) proposes; not just any staff actor.
        requireOneOf(OFFICER_ROLE, MANAGER_ROLE);
        collectionsService.assertCaseAccessible(caseId);
        CollectionCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("CollectionCase", String.valueOf(caseId)));
        Settlement s = new Settlement();
        s.setCollectionCaseId(caseId);
        s.setSettlementAmount(settlementAmountPaise);
        s.setProposedBy(actorStaffId());
        s.setCreatedAt(Instant.now());
        Settlement saved = settlementRepository.save(s);
        publishSettlement(saved, c.getLoanId(), false);
        return toView(saved);
    }

    /**
     * Collections Head approves a settlement. Enforces separation of duties: the
     * approver's staff id must differ from {@code proposedBy}.
     *
     * @throws BusinessException {@code SOD_VIOLATION} if the approver also proposed it
     */
    @Transactional
    public SettlementView approve(UUID settlementId) {
        // Only a Collection Head (or ADMIN) approves — in addition to the proposer≠approver SoD below.
        requireOneOf(MANAGER_ROLE);
        Settlement s = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
        requirePending(s);
        long approver = actorStaffId();
        Long proposedBy = s.getProposedBy();
        if (proposedBy != null && proposedBy == approver) {
            throw new BusinessException("SOD_VIOLATION",
                    "The approver must differ from the proposer (separation of duties)");
        }
        s.setStatus(SettlementStatus.APPROVED);
        s.setApprovedBy(approver);
        s.setApprovedAt(Instant.now());
        Settlement saved = settlementRepository.save(s);
        Long loanId = caseRepository.findById(saved.getCollectionCaseId())
                .map(CollectionCase::getLoanId).orElse(null);
        // The settlement caps what is owed from now on; if the borrower has already paid that much the
        // loan closes here, in the approval's own transaction, attributed to the approving Head.
        if (loanId != null) {
            loanDirectory.recomputeOutstanding(loanId);
        }
        publishSettlement(saved, loanId, true);
        return toView(saved);
    }

    /**
     * Collections Head rejects a proposed settlement. Enforces the same maker-checker rules as
     * {@link #approve}: only the Head (or ADMIN) may act, and the rejecter must differ from the
     * proposer. A rejected settlement is terminal and never caps the borrower's payable.
     *
     * @throws BusinessException {@code SETTLEMENT_NOT_PENDING} if already decided, or
     *                           {@code SOD_VIOLATION} if the rejecter also proposed it
     */
    @Transactional
    public SettlementView reject(UUID settlementId) {
        requireOneOf(MANAGER_ROLE);
        Settlement s = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
        requirePending(s);
        long rejecter = actorStaffId();
        Long proposedBy = s.getProposedBy();
        if (proposedBy != null && proposedBy == rejecter) {
            throw new BusinessException("SOD_VIOLATION",
                    "The approver must differ from the proposer (separation of duties)");
        }
        s.setStatus(SettlementStatus.REJECTED);
        s.setRejectedBy(rejecter);
        s.setRejectedAt(Instant.now());
        Settlement saved = settlementRepository.save(s);
        Long loanId = caseRepository.findById(saved.getCollectionCaseId())
                .map(CollectionCase::getLoanId).orElse(null);
        Long customerId = loanId == null ? null
                : loanDirectory.findLoan(loanId).map(LoanSummary::customerId).orElse(null);
        eventPublisher.publishEvent(new SettlementRejectedEvent(
                saved.getId(), saved.getCollectionCaseId(), loanId, customerId,
                saved.getSettlementAmount(), saved.getProposedBy(), Instant.now()));
        return toView(saved);
    }

    /** Guard: only a PROPOSED settlement can be approved or rejected. */
    private static void requirePending(Settlement s) {
        if (s.getStatus() != SettlementStatus.PROPOSED) {
            throw new BusinessException("SETTLEMENT_NOT_PENDING",
                    "This settlement has already been " + s.getStatus().name().toLowerCase());
        }
    }

    /** Publish the proposed/approved settlement event, resolving the borrower via the loan. */
    private void publishSettlement(Settlement s, Long loanId, boolean approved) {
        Long customerId = loanId == null ? null
                : loanDirectory.findLoan(loanId).map(LoanSummary::customerId).orElse(null);
        if (approved) {
            eventPublisher.publishEvent(new SettlementApprovedEvent(
                    s.getId(), s.getCollectionCaseId(), loanId, customerId,
                    s.getSettlementAmount(), s.getApprovedBy(), Instant.now()));
        } else {
            eventPublisher.publishEvent(new SettlementProposedEvent(
                    s.getId(), s.getCollectionCaseId(), loanId, customerId,
                    s.getSettlementAmount(), s.getProposedBy(), Instant.now()));
        }
    }

    /** One settlement by id. Staff-only, same guard as {@link #listAll()}. */
    @Transactional(readOnly = true)
    public Settlement getSettlement(UUID settlementId) {
        requireNonDsaStaff();
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", String.valueOf(settlementId)));
    }

    /**
     * All settlements (pending + approved), for the collections settlements worklist.
     *
     * <p>One {@link StaffDirectory#namesFor} batches every proposer/approver/rejecter name for the
     * whole page, rather than the up-to-three {@code findStaff} calls per row the single-row
     * {@link #toView(Settlement)} costs.
     *
     * <p>Guarded by {@link #requireNonDsaStaff()}: this read carried no authorization at all, so
     * any staff bearer — a DSA included — could list every settlement ever proposed.
     *
     * <p>Then narrowed to the collections roles (and ADMIN): each row now carries the borrower's
     * full PAN, bank account, email and bureau score, and a Telecaller, Accountant or credit role
     * calling the endpoint directly would otherwise read them for every settled borrower — wider
     * than their own customer scope. Those roles have no settlements screen to lose.
     */
    @Transactional(readOnly = true)
    public List<SettlementView> listAll() {
        requireNonDsaStaff();
        requireOneOf(OFFICER_ROLE, MANAGER_ROLE);
        Set<UUID> scoped = collectionsService.scopedCaseIdsOrNull();
        List<Settlement> settlements = settlementRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")).stream()
                .filter(s -> scoped == null || scoped.contains(s.getCollectionCaseId())).toList();
        Set<Long> staffIds = new HashSet<>();
        for (Settlement s : settlements) {
            staffIds.add(s.getProposedBy());
            staffIds.add(s.getApprovedBy());
            staffIds.add(s.getRejectedBy());
        }
        staffIds.removeIf(Objects::isNull);
        // Borrower + loan + dues behind each case, batched: one case read, one loan read and one
        // dues read for the whole page.
        Set<UUID> caseIds = new HashSet<>();
        settlements.forEach(s -> caseIds.add(s.getCollectionCaseId()));
        Map<UUID, CollectionCase> cases = new HashMap<>();
        Set<Long> loanIds = new HashSet<>();
        for (CollectionCase c : caseRepository.findAllById(caseIds)) {
            cases.put(c.getId(), c);
            if (c.getLoanId() != null) {
                loanIds.add(c.getLoanId());
            }
            if (c.getAssignedOfficerId() != null) {
                staffIds.add(c.getAssignedOfficerId());
            }
        }
        Map<Long, String> names = staffDirectory.namesFor(staffIds);
        Map<Long, LoanSummary> loans = loanIds.isEmpty() ? Map.of() : loanDirectory.findLoans(loanIds);
        Map<Long, LoanDues> dues = loanIds.isEmpty() ? Map.of() : loanDirectory.findDues(loanIds);
        return settlements.stream().map(s -> toView(s, names, cases, loans, dues)).toList();
    }

    private SettlementView toView(Settlement s) {
        CollectionCase c = caseRepository.findById(s.getCollectionCaseId()).orElse(null);
        Long loanId = c == null ? null : c.getLoanId();
        LoanSummary loan = loanId == null ? null : loanDirectory.findLoan(loanId).orElse(null);
        LoanDues dues = loanId == null ? null : loanDirectory.findDues(List.of(loanId)).get(loanId);
        return view(s, staffName(s.getProposedBy()), staffName(s.getApprovedBy()),
                staffName(s.getRejectedBy()), c == null ? null : staffName(c.getAssignedOfficerId()),
                loanId, loan, dues);
    }

    /** Batched variant of {@link #toView(Settlement)} for {@link #listAll()} — reads pre-resolved
     *  names, cases, loans and dues instead of resolving them per row. */
    private SettlementView toView(Settlement s, Map<Long, String> names, Map<UUID, CollectionCase> cases,
                                  Map<Long, LoanSummary> loans, Map<Long, LoanDues> dues) {
        CollectionCase c = cases.get(s.getCollectionCaseId());
        Long loanId = c == null ? null : c.getLoanId();
        Long officer = c == null ? null : c.getAssignedOfficerId();
        return view(s,
                s.getProposedBy() == null ? null : names.get(s.getProposedBy()),
                s.getApprovedBy() == null ? null : names.get(s.getApprovedBy()),
                s.getRejectedBy() == null ? null : names.get(s.getRejectedBy()),
                officer == null ? null : names.get(officer),
                loanId, loanId == null ? null : loans.get(loanId), loanId == null ? null : dues.get(loanId));
    }

    private static SettlementView view(Settlement s, String proposedByName, String approvedByName,
                                       String rejectedByName, String officerName, Long loanId,
                                       LoanSummary loan, LoanDues d) {
        SettlementStatus status = s.getStatus() != null ? s.getStatus() : SettlementStatus.PROPOSED;
        int dpd = loan == null || loan.dueDate() == null ? 0
                : DPD.daysPastDue(loan.dueDate(), LocalDate.now(IST));
        return new SettlementView(
                s.getId(), s.getCollectionCaseId(), s.getSettlementAmount(),
                s.getProposedBy(), proposedByName,
                s.getApprovedBy(), approvedByName,
                s.getRejectedBy(), rejectedByName,
                status.name(),
                s.getCreatedAt(), s.getApprovedAt(), s.getRejectedAt(),
                loanId,
                loan == null ? null : loan.customerId(),
                loan == null ? null : loan.borrowerName(),
                loan == null ? null : loan.mobile(),
                loan == null ? null : loan.status(),
                loan == null ? null : loan.principalPaise(),
                loan == null ? null : loan.outstandingPaise(),
                loan == null ? null : loan.dueDate(),
                dpd, DPD.bucket(dpd),
                loan == null ? null : loan.pan(),
                d == null ? null : d.email(),
                loan == null ? null : loan.employer(),
                loan == null ? null : loan.employmentStatus(),
                loan == null ? null : loan.monthlySalaryPaise(),
                loan == null ? null : loan.salaryBank(),
                d == null ? null : d.accountNumber(),
                d == null ? null : d.ifsc(),
                d == null ? null : d.bureauScore(),
                officerName,
                loan == null ? null : loan.disbursedOn(),
                loan == null ? null : loan.netDisbursedPaise(),
                d == null ? null : d.interestPaise(),
                d == null ? null : d.interestDays(),
                d == null ? null : d.penaltyPaise(),
                d == null ? null : d.penaltyDays(),
                d == null ? null : d.paidPaise(),
                d == null ? null : d.grossDuePaise());
    }

    private String staffName(Long staffId) {
        if (staffId == null) {
            return null;
        }
        return staffDirectory.findStaff(staffId).map(StaffSummary::name).orElse(null);
    }
}
