package com.navix.loan.service;

import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Loan-module implementation of the {@link LoanDirectory} port: resolves a real
 * loan plus its borrower (via the application that minted it and that application's
 * KYC {@link CustomerProfile}) into a cross-module {@link LoanSummary}. Mirrors
 * {@code StaffDirectoryAdapter} — wired by component scan, consumed by collections.
 */
@Component
@RequiredArgsConstructor
public class LoanDirectoryAdapter implements LoanDirectory {

    /**
     * Statuses a loan can be in to appear on the collections worklist. IN_COLLECTIONS belongs here
     * too: the worklist is now driven by loans rather than by hand-opened cases, so a loan already
     * flipped into collections must keep showing up in its DPD bucket.
     */
    private static final List<LoanStatus> COLLECTIBLE =
            List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);

    /** Every date here is an Indian calendar date; the server clock is UTC in ECS. */
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    private final LoanRepository loanRepository;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final RepaymentService repaymentService;

    @Override
    @Transactional(readOnly = true)
    public Optional<LoanSummary> findLoan(Long loanId) {
        if (loanId == null) {
            return Optional.empty();
        }
        return loanRepository.findById(loanId).map(this::toSummary);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reuses {@link #enrich}, the same batched resolution behind {@link #listCollectible} and
     * {@link #listUpcoming}, so a list-view caller (settlements, payments, cases) pays a fixed
     * number of queries for the whole page instead of the three per-row queries {@link #findLoan}
     * costs.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, LoanSummary> findLoans(Collection<Long> loanIds) {
        if (loanIds == null || loanIds.isEmpty()) {
            return new HashMap<>();
        }
        List<Long> ids = loanIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<Loan> loans = loanRepository.findAllById(ids);
        Map<Long, LoanSummary> result = new HashMap<>();
        for (LoanSummary s : enrich(loans, LocalDate.now(IST))) {
            result.put(s.loanId(), s);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanSummary> listCollectible(LocalDate asOf) {
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(IST);
        // Past due PLUS the next week: an officer chasing a borrower before salary day is the whole
        // point of the UPCOMING bucket, and until now a not-yet-due loan appeared nowhere.
        LocalDate horizon = effectiveAsOf.plusDays(PRE_DUE_WINDOW_DAYS);
        // Batched for the same reason listUpcoming is: this now reaches a week past today, so it is
        // no longer the short overdue tail that made per-row resolution acceptable, and it backs a
        // polled register.
        return enrich(loanRepository
                .findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(COLLECTIBLE, horizon),
                effectiveAsOf);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Batched deliberately: this list is effectively the entire live book (every advance runs to
     * at most {@code LoanMath.MAX_TERM_DAYS}, so almost everything outstanding is "not yet due"),
     * and per-row resolution would cost four queries each. The bulk finders and
     * {@link RepaymentService#outstandingForAll} already exist for exactly this shape -- see
     * {@code LoanRegisterService.list}, which enriches the loan register the same way.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LoanSummary> listUpcoming(LocalDate asOf) {
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(IST);
        return enrich(loanRepository
                .findByStatusInAndDueDateGreaterThanOrderByDueDateAsc(COLLECTIBLE, effectiveAsOf),
                effectiveAsOf);
    }

    /**
     * Resolve a page of loans into snapshots in a fixed number of queries: the applications, their
     * KYC profiles, and one {@link RepaymentService#outstandingForAll} pass — rather than the three
     * queries per row the single-loan {@link #findLoan} path costs.
     */
    private List<LoanSummary> enrich(List<Loan> loans, LocalDate asOf) {
        if (loans.isEmpty()) {
            // Short-circuit: the *In finders below are not valid SQL against an empty collection.
            return List.of();
        }
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        Map<Long, LoanApplication> appByLoanId = applicationRepository.findByLoanIdIn(loanIds).stream()
                .filter(a -> a.getLoanId() != null)
                .collect(Collectors.toMap(LoanApplication::getLoanId, a -> a, (a, b) -> a));
        List<Long> appIds = appByLoanId.values().stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> profileByAppId = appIds.isEmpty() ? Map.of()
                : profileRepository.findByApplicationIdIn(appIds).stream()
                        .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        Map<Long, Long> owedByLoanId = repaymentService.outstandingForAll(loans, asOf);
        return loans.stream().map(loan -> {
            LoanApplication app = appByLoanId.get(loan.getId());
            CustomerProfile profile = app != null ? profileByAppId.get(app.getId()) : null;
            return toSummary(loan, app, profile, owedByLoanId.getOrDefault(loan.getId(), 0L));
        }).toList();
    }

    @Override
    @Transactional
    public void markInCollections(Long loanId) {
        if (loanId == null) {
            return;
        }
        loanRepository.findById(loanId).ifPresent(loan -> {
            // Only once the loan is genuinely past due. A pre-emptive case on a loan that is still
            // running to term is a courtesy call, not a delinquency: flipping the status would show
            // an on-time borrower as in-collections in every segment, queue and dashboard count.
            // Called unconditionally by the daily reminder sweep, so the flip lands the day it is due.
            boolean pastDue = loan.getDueDate() != null && LocalDate.now(IST).isAfter(loan.getDueDate());
            if (pastDue && (loan.getStatus() == LoanStatus.ACTIVE || loan.getStatus() == LoanStatus.OVERDUE)) {
                loan.setStatus(LoanStatus.IN_COLLECTIONS);
                loanRepository.save(loan);
            }
        });
    }

    /**
     * Post a validated collections payment to the ledger. Recorded and verified in one step: the
     * Accountant's validation is the verification, so leaving the row PENDING_VERIFICATION would
     * queue the same payment for them a second time. Verifying recomputes the penalty-aware
     * outstanding and closes the loan (and its application) at zero.
     */
    @Override
    @Transactional
    public Long creditCollectionPayment(Long loanId, long amountPaise, String txnRef,
                                        String proofRef, LocalDate paidOn) {
        // BANK_TRANSFER is the honest default: collections captures a reference and a proof, not the
        // rail. The method is display metadata here — nothing in the math branches on it.
        var payment = repaymentService.recordPayment(
                loanId, amountPaise, PaymentMethod.BANK_TRANSFER, txnRef, proofRef, paidOn);
        repaymentService.verifyPayment(payment.getId());
        return payment.getId();
    }

    /** Build the snapshot, resolving the borrower via the application's KYC profile (both nullable). */
    private LoanSummary toSummary(Loan loan) {
        LoanApplication app = applicationRepository.findByLoanId(loan.getId()).orElse(null);
        CustomerProfile profile = app != null
                ? profileRepository.findByApplicationId(app.getId()).orElse(null)
                : null;
        return toSummary(loan, app, profile, repaymentService.outstandingAsOf(loan.getId(), null));
    }

    /**
     * The snapshot builder proper, over collaborators the caller has already resolved. Split out so
     * a caller holding a whole page of loans ({@link #enrich}) can batch those lookups once
     * instead of paying for them per row, while single-loan callers keep the convenience overload
     * above. Both paths therefore produce a byte-identical {@link LoanSummary}.
     */
    private LoanSummary toSummary(Loan loan, LoanApplication app, CustomerProfile profile, long owed) {
        // Effective status (ACTIVE → OVERDUE past due) and the penalty/prepayment-aware balance, so
        // collections shows the same "amount owed" the borrower sees on the repay page.
        LoanStatus effective = loan.effectiveStatus(LocalDate.now(IST));
        return new LoanSummary(
                loan.getId(),
                loan.getCustomerId(),
                app != null ? app.getId() : null,
                effective != null ? effective.name() : null,
                loan.getPrincipal(),
                loan.getNetDisbursed(),
                loan.getTotalRepayable(),
                owed,
                loan.getDisbursedOn(),
                loan.getDueDate(),
                profile != null ? profile.getFullName() : null,
                profile != null ? profile.getPan() : null,
                profile != null ? profile.getEmployer() : null,
                profile != null ? profile.getEmploymentStatus() : null,
                profile != null ? profile.getMonthlySalaryPaise() : null,
                profile != null ? profile.getSalaryBank() : null,
                loan.getDueDate() != null && loan.getDueDate().isAfter(LocalDate.now(IST)));
    }
}
