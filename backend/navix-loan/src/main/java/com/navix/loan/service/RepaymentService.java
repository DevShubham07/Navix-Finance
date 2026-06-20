package com.navix.loan.service;

import com.navix.loan.entity.Payment;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Records and verifies repayments against a loan.
 *
 * <p>Rules: prepayment is allowed anytime (interest only to the day of
 * repayment, no penalty); partial payments are allowed and the loan closes when
 * the outstanding balance reaches zero. A "paid" claim requires proof.
 *
 * TODO: implement persistence + Loan.outstanding recomputation + status
 * transition to REPAID / CLOSED.
 */
@Service
public class RepaymentService {

    /** Record a (possibly partial) repayment; returns the persisted Payment. */
    public Payment recordPayment(Long loanId, Payment payment) {
        // TODO: persist payment, recompute outstanding, transition loan status.
        throw new UnsupportedOperationException("RepaymentService.recordPayment not implemented yet");
    }

    /** Mark a repayment VERIFIED once proof is confirmed. */
    public Payment verifyPayment(Long paymentId) {
        // TODO: set status VERIFIED and recompute outstanding.
        throw new UnsupportedOperationException("RepaymentService.verifyPayment not implemented yet");
    }

    /** List all repayments recorded against a loan. */
    public List<Payment> listPayments(Long loanId) {
        // TODO: return payments for the loan.
        throw new UnsupportedOperationException("RepaymentService.listPayments not implemented yet");
    }
}
