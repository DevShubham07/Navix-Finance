package com.navix.loan.service;

import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.util.Masking;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Loan-module implementation of the {@link LoanDirectory} port: resolves a real
 * loan plus its borrower (via the application that minted it and that application's
 * KYC {@link ApplicantProfile}) into a cross-module {@link LoanSummary}. Mirrors
 * {@code StaffDirectoryAdapter} — wired by component scan, consumed by collections.
 */
@Component
@RequiredArgsConstructor
public class LoanDirectoryAdapter implements LoanDirectory {

    /** Statuses a loan can be in to still be eligible for a fresh collections case. */
    private static final List<LoanStatus> COLLECTIBLE = List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);

    private final LoanRepository loanRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ApplicantProfileRepository profileRepository;
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

    /** Build the snapshot, resolving the borrower via the application's KYC profile (both nullable). */
    private LoanSummary toSummary(Loan loan) {
        LoanApplication app = applicationRepository.findByLoanId(loan.getId()).orElse(null);
        Long applicationId = app != null ? app.getId() : null;
        ApplicantProfile profile = applicationId != null
                ? profileRepository.findByApplicationId(applicationId).orElse(null)
                : null;
        // Effective status (ACTIVE → OVERDUE past due) and the penalty/prepayment-aware balance, so
        // collections shows the same "amount owed" the borrower sees on the repay page.
        LoanStatus effective = loan.effectiveStatus(LocalDate.now());
        long owed = repaymentService.outstandingAsOf(loan.getId(), null);
        return new LoanSummary(
                loan.getId(),
                loan.getApplicantId(),
                applicationId,
                effective != null ? effective.name() : null,
                loan.getPrincipal(),
                loan.getNetDisbursed(),
                loan.getTotalRepayable(),
                owed,
                loan.getDisbursedOn(),
                loan.getDueDate(),
                profile != null ? profile.getFullName() : null,
                profile != null ? Masking.maskPan(profile.getPan()) : null,
                profile != null ? profile.getEmployer() : null,
                profile != null ? profile.getEmploymentStatus() : null,
                profile != null ? profile.getMonthlySalaryPaise() : null,
                profile != null ? profile.getSalaryBank() : null);
    }
}
