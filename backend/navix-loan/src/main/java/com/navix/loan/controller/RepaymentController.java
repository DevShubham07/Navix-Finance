package com.navix.loan.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repayment endpoints for a loan: manual UPI / bank-transfer repayment with
 * proof, supporting partial payments and prepayment. NACH auto-debit is
 * [FUTURE].
 *
 * TODO: define request/response DTOs and wire to {@code RepaymentService}.
 */
@RestController
@RequestMapping("/api/loan/{loanId}/repayments")
public class RepaymentController {

    /** Record a repayment (full, partial or prepayment) against the loan. */
    @PostMapping
    public Object record(@PathVariable Long loanId) {
        // TODO: accept RepaymentRequest (amount, method, txnRef, proof) and delegate.
        throw new UnsupportedOperationException("RepaymentController.record not implemented yet");
    }

    /** List repayments recorded against the loan. */
    @GetMapping
    public Object list(@PathVariable Long loanId) {
        // TODO: return repayment history.
        throw new UnsupportedOperationException("RepaymentController.list not implemented yet");
    }
}
