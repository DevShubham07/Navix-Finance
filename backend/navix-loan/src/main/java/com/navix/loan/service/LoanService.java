package com.navix.loan.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and reads the disbursed {@link Loan} (money/ledger). The application lifecycle itself is
 * owned by {@code ApplicationFlowService}; this service is invoked at the DISBURSED→ACTIVE step to
 * create the financial loan, computing the economics in paise via {@link LoanMath}.
 *
 * <p>Due date is salary-linked: the latest salary credit ≤ 40 days after disbursement (per product
 * owner), so the term varies and interest accrues 1%/day over the actual tenure.
 */
@Service
@RequiredArgsConstructor
public class LoanService {

    /** Default salary-credit day used when an application did not capture one. */
    private static final int DEFAULT_SALARY_DAY = 1;

    private final LoanRepository loanRepository;
    private final LoanMath loanMath;

    @Transactional(readOnly = true)
    public Loan getLoan(Long id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(id)));
    }

    /**
     * Create the ACTIVE loan for a disbursed application: fee/GST/net + a salary-linked due date
     * (latest salary credit ≤ 40 days out) with interest over the actual tenure as the opening
     * outstanding balance.
     */
    @Transactional
    public Loan disburse(LoanApplication application, LocalDate disbursedOn, String disbursalTxnRef) {
        LocalDate disbursed = disbursedOn != null ? disbursedOn : LocalDate.now();
        long principal = application.getAmountRequested();
        int salaryDay = application.getSalaryCreditDay() != null
                ? application.getSalaryCreditDay()
                : DEFAULT_SALARY_DAY;
        LocalDate dueDate = loanMath.dueDateFromSalary(disbursed, salaryDay);
        int tenureDays = (int) java.time.temporal.ChronoUnit.DAYS.between(disbursed, dueDate);

        Loan loan = new Loan();
        loan.setApplicantId(application.getApplicantId());
        loan.setPrincipal(principal);
        loan.setProcessingFee(loanMath.processingFeePaise(principal));
        loan.setGst(loanMath.gstPaise(principal));
        loan.setNetDisbursed(loanMath.netDisbursedPaise(principal));
        loan.setDailyInterestRate(LoanMath.DAILY_INTEREST_RATE.setScale(4, java.math.RoundingMode.UNNECESSARY));
        loan.setDisbursedOn(disbursed);
        loan.setDueDate(dueDate);
        long total = loanMath.totalRepayablePaise(principal, tenureDays);
        loan.setTotalRepayable(total);
        loan.setOutstanding(total);
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setDisbursalTxnRef(disbursalTxnRef != null && !disbursalTxnRef.isBlank() ? disbursalTxnRef : null);
        return loanRepository.save(loan);
    }
}
