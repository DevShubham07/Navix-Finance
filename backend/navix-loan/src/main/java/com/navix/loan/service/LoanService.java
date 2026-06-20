package com.navix.loan.service;

import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the loan lifecycle: application, review, approval, document
 * signing, bank verification, disbursement, repayment and closure.
 *
 * <p>Enforces maker-checker separation (Credit Executive review != Credit
 * Head approve != Disbursement Head release; Accountant confirms transfer).
 *
 * TODO: implement state transitions and persistence; collaborate with
 * {@code EligibilityService} and {@code LoanMath}.
 */
@Service
public class LoanService {

    /**
     * Create a new loan application.
     *
     * TODO: implement.
     */
    public LoanApplication apply(Long applicantId, java.math.BigDecimal amountRequested) {
        // TODO: validate, persist a new APPLIED application.
        throw new UnsupportedOperationException("LoanService.apply not implemented yet");
    }

    /**
     * Materialize an approved application into a {@link Loan} with computed math.
     *
     * TODO: implement.
     */
    public Loan createLoanFromApplication(Long applicationId) {
        // TODO: compute fee/gst/net/dueDate/totalRepayable via LoanMath.
        throw new UnsupportedOperationException("LoanService.createLoanFromApplication not implemented yet");
    }
}
