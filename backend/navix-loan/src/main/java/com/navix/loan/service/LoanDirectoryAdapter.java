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
import java.util.List;
import java.util.Map;
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

    /** Statuses a loan can be in to still be eligible for a fresh collections case. */
    private static final List<LoanStatus> COLLECTIBLE = List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);

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

    @Override
    @Transactional(readOnly = true)
    public List<LoanSummary> listCollectible(LocalDate asOf) {
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        return loanRepository
                .findByStatusInAndDueDateLessThanEqualOrderByDueDateAsc(COLLECTIBLE, effectiveAsOf)
                .stream().map(this::toSummary).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Batched deliberately. {@link #listCollectible} only ever sees the overdue tail, so mapping
     * it through the per-loan {@code toSummary} is fine; this list is effectively the entire live
     * book (every advance runs to at most {@code LoanMath.MAX_TERM_DAYS}, so almost everything
     * outstanding is "not yet due"), and per-row resolution would cost four queries each. The bulk
     * finders and {@link RepaymentService#outstandingForAll} already exist for exactly this shape --
     * see {@code LoanRegisterService.list}, which enriches the loan register the same way.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LoanSummary> listUpcoming(LocalDate asOf) {
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        List<Loan> loans = loanRepository
                .findByStatusInAndDueDateGreaterThanOrderByDueDateAsc(COLLECTIBLE, effectiveAsOf);
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
        Map<Long, Long> owedByLoanId = repaymentService.outstandingForAll(loans, effectiveAsOf);
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
            if (loan.getStatus() == LoanStatus.ACTIVE || loan.getStatus() == LoanStatus.OVERDUE) {
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
     * a caller holding a whole page of loans ({@link #listUpcoming}) can batch those lookups once
     * instead of paying for them per row, while single-loan callers keep the convenience overload
     * above. Both paths therefore produce a byte-identical {@link LoanSummary}.
     */
    private LoanSummary toSummary(Loan loan, LoanApplication app, CustomerProfile profile, long owed) {
        // Effective status (ACTIVE → OVERDUE past due) and the penalty/prepayment-aware balance, so
        // collections shows the same "amount owed" the borrower sees on the repay page.
        LoanStatus effective = loan.effectiveStatus(LocalDate.now());
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
                profile != null ? profile.getSalaryBank() : null);
    }
}
