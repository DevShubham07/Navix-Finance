package com.navix.loan.service;

import com.navix.common.collections.SettlementDirectory;
import com.navix.common.notification.event.SettlementApprovedEvent;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.DsaCommission;
import com.navix.loan.entity.DsaCommissionEvent;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.DsaCommissionEventRepository;
import com.navix.loan.repository.DsaCommissionRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * The DSA commission accrual / maturity / void engine (V55). Mirrors {@code ReferralService}'s
 * accrual-ledger shape:
 *
 * <ul>
 *   <li>{@link #onLoanDisbursed} — called in-band from
 *       {@code ApplicationFlowService.finalizeDisbursal}, atomic with the loan mint. Creates the
 *       single ACCRUED row for a lead's first loan, or silently does nothing.</li>
 *   <li>{@link #onLoanClosed} — called from {@code RepaymentService.recomputeOutstanding} right
 *       after a loan closes clean. Decides PAYABLE vs VOID (approved settlement / default /
 *       write-off).</li>
 *   <li>{@link #onSettlementApproved} — a transactional event listener on
 *       {@link SettlementApprovedEvent} (published by {@code navix-collections}'
 *       {@code SettlementService}). Voids an ACCRUED commission the moment a settlement is
 *       approved, rather than waiting for the loan to close.</li>
 * </ul>
 *
 * <p>{@link #DSA_COMMISSION_RATE_BPS} is snapshotted onto every row at accrual time, so a later
 * rate change never rewrites history. All money is integer paise, {@link RoundingMode#HALF_UP},
 * mirroring {@link LoanMath}.
 */
@Service
@RequiredArgsConstructor
public class DsaCommissionService {

    private static final Logger log = LoggerFactory.getLogger(DsaCommissionService.class);

    /** 3.5% flat, snapshotted per row. */
    static final int DSA_COMMISSION_RATE_BPS = 350;

    private final DsaCommissionRepository commissionRepository;
    private final DsaCommissionEventRepository commissionEventRepository;
    private final LeadRepository leadRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final LoanRepository loanRepository;
    private final LoanApplicationRepository applicationRepository;
    private final SettlementDirectory settlementDirectory;

    /**
     * Accrue a DSA commission for {@code app}/{@code loan} if — and only if — the borrower's PAN
     * matches an existing DSA lead entered before this application, this is the customer's first
     * loan ever, and no commission already exists for that lead. Never throws: a commission failure
     * must not block a disbursal.
     */
    @Transactional
    public void onLoanDisbursed(LoanApplication app, Loan loan) {
        try {
            accrue(app, loan);
        } catch (Exception e) {
            log.warn("DSA commission accrual failed for application {} / loan {}: {}",
                    app != null ? app.getId() : null, loan != null ? loan.getId() : null, e.getMessage(), e);
        }
    }

    private void accrue(LoanApplication app, Loan loan) {
        if (app == null || loan == null) {
            return;
        }
        Optional<CustomerProfile> profile = customerProfileRepository.findByApplicationId(app.getId());
        if (profile.isEmpty() || profile.get().getPan() == null || profile.get().getPan().isBlank()) {
            return;
        }
        String pan = profile.get().getPan().trim().toUpperCase();
        Optional<Lead> leadOpt = leadRepository.findByPanAndOwnerDsaIdNotNull(pan);
        if (leadOpt.isEmpty()) {
            return;
        }
        Lead lead = leadOpt.get();
        if (lead.getCreatedAt() == null || app.getCreatedAt() == null
                || !lead.getCreatedAt().isBefore(app.getCreatedAt())) {
            // The application predates (or ties) the lead — this file was already in flight, not
            // sourced by the DSA.
            return;
        }
        if (commissionRepository.findByLeadId(lead.getId()).isPresent()) {
            return;
        }
        if (loanRepository.countByCustomerId(app.getCustomerId()) != 1) {
            // Not the customer's first loan — repeat borrowing is deliberately invisible to the DSA.
            return;
        }
        long netDisbursed = loan.getNetDisbursed() != null ? loan.getNetDisbursed() : 0L;
        long amount = roundPaise(BigDecimal.valueOf(netDisbursed)
                .multiply(BigDecimal.valueOf(DSA_COMMISSION_RATE_BPS))
                .divide(BigDecimal.valueOf(10_000)));

        DsaCommission commission = new DsaCommission();
        commission.setDsaStaffId(lead.getOwnerDsaId());
        commission.setLeadId(lead.getId());
        commission.setCustomerId(app.getCustomerId());
        commission.setApplicationId(app.getId());
        commission.setLoanId(loan.getId());
        commission.setNetDisbursedPaise(netDisbursed);
        commission.setRateBps(DSA_COMMISSION_RATE_BPS);
        commission.setAmountPaise(amount);
        commission.setStatus(DsaCommissionStatus.ACCRUED);
        commission.setAccruedAt(Instant.now());
        commissionRepository.save(commission);
        log.info("DSA commission accrued: dsa={} lead={} loan={} amount={}paise",
                lead.getOwnerDsaId(), lead.getId(), loan.getId(), amount);
    }

    /**
     * A loan just closed clean (outstanding hit zero). Idempotent: only acts on a still-ACCRUED
     * row. Decides PAYABLE vs VOID — an approved settlement or a DEFAULTED/WRITTEN_OFF outcome
     * voids the commission; a clean close makes it PAYABLE.
     */
    @Transactional
    public void onLoanClosed(Long loanId) {
        if (loanId == null) {
            return;
        }
        commissionRepository.findByLoanId(loanId).ifPresent(commission -> {
            if (commission.getStatus() != DsaCommissionStatus.ACCRUED) {
                return;
            }
            if (isVoidOutcome(loanId)) {
                voidCommission(commission, "settlement approved or loan defaulted/written off", null);
            } else {
                commission.setStatus(DsaCommissionStatus.PAYABLE);
                commission.setPayableAt(Instant.now());
                commissionRepository.save(commission);
            }
        });
    }

    private boolean isVoidOutcome(Long loanId) {
        if (settlementDirectory.approvedSettlementAmount(loanId).isPresent()) {
            return true;
        }
        return applicationRepository.findByLoanId(loanId)
                .map(LoanApplication::getStatus)
                .map(s -> s == ApplicationStatus.DEFAULTED || s == ApplicationStatus.WRITTEN_OFF)
                .orElse(false);
    }

    /**
     * A Collection Head just approved a settlement — void any still-ACCRUED commission on that loan
     * immediately, rather than waiting for closure, so a settled loan never surfaces as payable even
     * transiently.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementApproved(SettlementApprovedEvent event) {
        if (event.loanId() == null) {
            return;
        }
        commissionRepository.findByLoanId(event.loanId()).ifPresent(commission -> {
            if (commission.getStatus() == DsaCommissionStatus.ACCRUED) {
                voidCommission(commission, "settlement approved", null);
            }
        });
    }

    private void voidCommission(DsaCommission commission, String reason, Long actorId) {
        commission.setStatus(DsaCommissionStatus.VOID);
        commission.setVoidReason(reason);
        commission.setVoidedAt(Instant.now());
        commissionRepository.save(commission);
        DsaCommissionEvent event = new DsaCommissionEvent();
        event.setCommissionId(commission.getId());
        event.setAction("VOIDED");
        event.setActorId(actorId);
        event.setNotes(reason);
        commissionEventRepository.save(event);
        log.info("DSA commission {} voided: {}", commission.getId(), reason);
    }

    private static long roundPaise(BigDecimal paise) {
        return paise.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
