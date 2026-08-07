package com.navix.collections.service;

import com.navix.collections.dto.CollectionsDtos.CollectionPaymentView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentKind;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.CollectionPaymentRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.event.CollectionPaymentDecidedEvent;
import com.navix.common.notification.event.CollectionPaymentRaisedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money collected in the field, and the checker who lets it touch the ledger (V47; revamp.md
 * decisions 43, 44).
 *
 * <p>The shape of the rule: <b>the person who takes a payment never books it.</b> A Collection
 * Executive (or their Head) records what the borrower paid; the <b>Accountant</b> — who can see the
 * borrower's whole loan cycle — validates it against the bank, and only that validation credits the
 * loan. A settlement takes one extra hop first, because it writes off part of the debt and that
 * concession is the Collection Head's to make.
 *
 * <pre>
 *   PART_PAYMENT / FULL_PAYMENT  →  PENDING_ACCOUNTANT  →  VALIDATED (loan credited)
 *   SETTLEMENT   →  PENDING_HEAD →  PENDING_ACCOUNTANT  →  VALIDATED (loan credited)
 *                        └──────────────── REJECTED (terminal, with remarks) ──┘
 * </pre>
 *
 * <p>All amounts are integer paise.
 */
@Service
@RequiredArgsConstructor
public class CollectionPaymentService {

    private static final String OFFICER_ROLE = "COLLECTION_EXECUTIVE";
    private static final String HEAD_ROLE = "COLLECTION_HEAD";
    private static final String CHECKER_ROLE = "ACCOUNTANT";

    private final CollectionPaymentRepository paymentRepository;
    private final CollectionCaseRepository caseRepository;
    private final SettlementRepository settlementRepository;
    // Releasing a settlement payment approves the concession behind it — see headApprove.
    private final SettlementService settlementService;
    private final LoanDirectory loanDirectory;
    private final StaffDirectory staffDirectory;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Record a payment taken on a case. A settlement must name an <b>approved</b> settlement — the
     * concession has to exist before there is anything to settle against, and requiring it here is
     * what stops "settlement" being used to write down a balance nobody agreed to.
     */
    @Transactional
    public CollectionPaymentView raise(UUID caseId, CollectionPaymentKind kind, long amountPaise,
                                       LocalDate paidOn, String txnRef, String proofRef,
                                       UUID settlementId) {
        requireOneOf(OFFICER_ROLE, HEAD_ROLE);
        if (amountPaise <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Payment amount must be positive");
        }
        CollectionCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("CollectionCase", String.valueOf(caseId)));

        CollectionPayment p = new CollectionPayment();
        p.setCollectionCaseId(caseId);
        p.setLoanId(c.getLoanId());
        p.setKind(kind);
        p.setAmountPaise(amountPaise);
        p.setPaidOn(paidOn != null ? paidOn : LocalDate.now());
        p.setTxnRef(blankToNull(txnRef));
        p.setProofRef(blankToNull(proofRef));
        p.setRaisedBy(actorStaffId());
        p.setRaisedAt(Instant.now());

        if (kind.needsHeadApproval()) {
            p.setSettlementId(requireProposedOrApprovedSettlement(caseId, settlementId));
            // Already approved by the Head? Then the approval it was waiting for has happened and it
            // goes straight on to the Accountant.
            boolean approved = settlementRepository.findById(p.getSettlementId())
                    .map(s -> s.getStatus() == SettlementStatus.APPROVED).orElse(false);
            p.setStatus(approved ? CollectionPaymentStatus.PENDING_ACCOUNTANT
                    : CollectionPaymentStatus.PENDING_HEAD);
        } else {
            p.setStatus(CollectionPaymentStatus.PENDING_ACCOUNTANT);
        }

        CollectionPayment saved = paymentRepository.save(p);
        eventPublisher.publishEvent(new CollectionPaymentRaisedEvent(
                saved.getId(), caseId, saved.getLoanId(), customerIdOf(saved.getLoanId()),
                amountPaise, kind.name(),
                saved.getStatus() == CollectionPaymentStatus.PENDING_HEAD,
                saved.getRaisedBy(), Instant.now()));
        return toView(saved);
    }

    /**
     * The Collection Head clears a settlement payment through to the Accountant — <b>one</b> click,
     * which also approves the concession it settles if that hasn't happened yet.
     *
     * <p>Splitting those into two approvals was a bug, not a nicety: an un-approved settlement never
     * caps the borrower's payable ({@code RepaymentService.outstandingAsOf} only reads
     * <em>approved</em> ones), so a paid full-and-final left the loan open for the difference — the
     * exact thing the concession was supposed to write off. The Head's decision is one decision, so
     * it lands on both records. {@link SettlementService#approve} still applies its own
     * proposer ≠ approver check on the settlement.
     */
    @Transactional
    public CollectionPaymentView headApprove(UUID paymentId) {
        requireOneOf(HEAD_ROLE);
        CollectionPayment p = require(paymentId);
        if (p.getStatus() != CollectionPaymentStatus.PENDING_HEAD) {
            throw new BusinessException("PAYMENT_NOT_PENDING_HEAD",
                    "This payment is not waiting for a Collection Head");
        }
        long approver = actorStaffId();
        if (p.getRaisedBy() != null && p.getRaisedBy() == approver) {
            throw new BusinessException("SOD_VIOLATION",
                    "The approver must differ from the officer who recorded the payment");
        }
        if (p.getSettlementId() != null) {
            settlementRepository.findById(p.getSettlementId())
                    .filter(s -> s.getStatus() == SettlementStatus.PROPOSED)
                    .ifPresent(s -> settlementService.approve(s.getId()));
        }
        p.setStatus(CollectionPaymentStatus.PENDING_ACCOUNTANT);
        CollectionPayment saved = paymentRepository.save(p);
        eventPublisher.publishEvent(new CollectionPaymentRaisedEvent(
                saved.getId(), saved.getCollectionCaseId(), saved.getLoanId(),
                customerIdOf(saved.getLoanId()), saved.getAmountPaise(), saved.getKind().name(),
                false, saved.getRaisedBy(), Instant.now()));
        return toView(saved);
    }

    /**
     * The Accountant validates the payment against the bank and credits the loan, or rejects it with
     * remarks. Validation is the <b>only</b> path by which a collections payment reaches the ledger.
     *
     * <p>Remarks are required on a rejection: the officer has to be able to act on it (wrong amount?
     * unmatched UTR? paid to the wrong account?) rather than just see the payment vanish.
     */
    @Transactional
    public CollectionPaymentView validate(UUID paymentId, boolean accept, String remarks) {
        requireOneOf(CHECKER_ROLE);
        CollectionPayment p = require(paymentId);
        if (p.getStatus() != CollectionPaymentStatus.PENDING_ACCOUNTANT) {
            throw new BusinessException("PAYMENT_NOT_PENDING_VALIDATION",
                    p.getStatus() == CollectionPaymentStatus.PENDING_HEAD
                            ? "The Collection Head has not approved this settlement yet"
                            : "This payment has already been " + p.getStatus().name().toLowerCase());
        }
        if (!accept && (remarks == null || remarks.isBlank())) {
            throw new BusinessException("REMARKS_REQUIRED",
                    "Say why you are rejecting this payment — the collections officer needs to act on it");
        }
        p.setValidatedBy(actorStaffId());
        p.setValidatedAt(Instant.now());
        p.setRemarks(blankToNull(remarks));
        if (accept) {
            // The one write into the loan ledger. Guarded by ledgerPaymentId so a retry after a
            // partial failure — or a double-click — can't credit the borrower's loan twice.
            if (p.getLedgerPaymentId() == null) {
                p.setLedgerPaymentId(loanDirectory.creditCollectionPayment(
                        p.getLoanId(), p.getAmountPaise(), p.getTxnRef(), p.getProofRef(), p.getPaidOn()));
            }
            p.setStatus(CollectionPaymentStatus.VALIDATED);
        } else {
            p.setStatus(CollectionPaymentStatus.REJECTED);
        }
        CollectionPayment saved = paymentRepository.save(p);
        eventPublisher.publishEvent(new CollectionPaymentDecidedEvent(
                saved.getId(), saved.getCollectionCaseId(), saved.getLoanId(),
                customerIdOf(saved.getLoanId()), saved.getAmountPaise(), accept,
                saved.getRemarks(), saved.getRaisedBy(), Instant.now()));
        return toView(saved);
    }

    // ---- reads --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CollectionPaymentView> listForCase(UUID caseId) {
        return paymentRepository.findByCollectionCaseIdOrderByRaisedAtDesc(caseId).stream()
                .map(this::toView).toList();
    }

    /** The Accountant's validation queue, or the Collection Head's approval queue. */
    @Transactional(readOnly = true)
    public List<CollectionPaymentView> listByStatus(CollectionPaymentStatus status) {
        return paymentRepository.findByStatusOrderByRaisedAtAsc(status).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<CollectionPaymentView> listAll() {
        return paymentRepository.findAllByOrderByRaisedAtDesc().stream().map(this::toView).toList();
    }

    // ---- helpers ------------------------------------------------------------------------

    /**
     * Resolve the settlement a settlement-payment settles: the one given, else the case's only live
     * one. Rejected settlements are never acceptable — a concession the Head turned down cannot be
     * paid against.
     */
    private UUID requireProposedOrApprovedSettlement(UUID caseId, UUID settlementId) {
        List<Settlement> live = settlementRepository.findByCollectionCaseId(caseId).stream()
                .filter(s -> s.getStatus() != SettlementStatus.REJECTED)
                .toList();
        if (settlementId != null) {
            return live.stream().map(Settlement::getId).filter(settlementId::equals).findFirst()
                    .orElseThrow(() -> new BusinessException("SETTLEMENT_NOT_FOUND",
                            "That settlement is not open on this case"));
        }
        if (live.size() != 1) {
            throw new BusinessException("SETTLEMENT_REQUIRED",
                    live.isEmpty()
                            ? "Propose a settlement on this case before recording a settlement payment"
                            : "This case has more than one open settlement — say which one this pays");
        }
        return live.get(0).getId();
    }

    private CollectionPayment require(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("CollectionPayment", String.valueOf(paymentId)));
    }

    private Long customerIdOf(Long loanId) {
        return loanId == null ? null
                : loanDirectory.findLoan(loanId).map(LoanSummary::customerId).orElse(null);
    }

    private CollectionPaymentView toView(CollectionPayment p) {
        return new CollectionPaymentView(
                p.getId(), p.getCollectionCaseId(), p.getLoanId(),
                p.getKind().name(), p.getAmountPaise(), p.getPaidOn(),
                p.getTxnRef(), p.getProofRef(), p.getSettlementId(),
                p.getStatus().name(),
                p.getRaisedBy(), staffName(p.getRaisedBy()), p.getRaisedAt(),
                p.getValidatedBy(), staffName(p.getValidatedBy()), p.getValidatedAt(),
                p.getRemarks(), p.getLedgerPaymentId(),
                borrowerName(p.getLoanId()));
    }

    private String borrowerName(Long loanId) {
        return loanId == null ? null
                : loanDirectory.findLoan(loanId).map(LoanSummary::borrowerName).orElse(null);
    }

    private String staffName(Long staffId) {
        return staffId == null ? null
                : staffDirectory.findStaff(staffId).map(StaffSummary::name).orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

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

    private static long actorStaffId() {
        try {
            return Long.parseLong(ActorContext.get().id());
        } catch (NumberFormatException e) {
            throw new BusinessException("ACTOR_NOT_STAFF",
                    "The acting identity is not a real staff id; sign in as staff");
        }
    }
}
