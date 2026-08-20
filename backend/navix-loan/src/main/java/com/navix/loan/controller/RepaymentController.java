package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.LoanDtos.RejectRepaymentRequest;
import com.navix.loan.dto.LoanDtos.RepaymentRequest;
import com.navix.loan.service.RepaymentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repayment endpoints for a loan: manual UPI / bank-transfer repayment with proof, supporting
 * partial payments and prepayment. A recorded payment is PENDING until verified; verification
 * reduces the outstanding balance and closes the loan at zero. NACH auto-debit is [FUTURE].
 */
@RestController
@RequestMapping("/api/loan/{loanId}/repayments")
@RequiredArgsConstructor
public class RepaymentController {

    private final RepaymentService repaymentService;

    /** Record a repayment (full, partial or prepayment) against the loan. */
    @PostMapping
    public ApiResponse<PaymentView> record(@PathVariable Long loanId,
                                           @Valid @RequestBody RepaymentRequest request) {
        return ApiResponse.ok(repaymentService.view(repaymentService.recordPayment(
                loanId, request.amountPaise(), request.method(), request.txnRef(),
                request.proofUrl(), request.paidOn())));
    }

    /** List repayments recorded against the loan. */
    @GetMapping
    public ApiResponse<List<PaymentView>> list(@PathVariable Long loanId) {
        return ApiResponse.ok(repaymentService.listPayments(loanId).stream()
                .map(repaymentService::view)
                .toList());
    }

    /**
     * Confirm proof for a recorded payment — <b>Accountant/Admin only</b>.
     *
     * <p>This is the single call that turns a claimed payment into money the ledger believes in: it
     * reduces the outstanding and closes the loan at zero. It carried no role check at all, so any
     * caller with a valid token could make it — including a Collection Executive chasing the debt,
     * a Telecaller, or <b>the borrower themselves</b>, who could record a payment with an invented
     * transaction reference and then verify it, writing off their own loan without paying.
     * The counterpart {@link #reject} was guarded; this was not.
     */
    @PostMapping("/{paymentId}/verify")
    public ApiResponse<PaymentView> verify(@PathVariable Long loanId, @PathVariable Long paymentId) {
        requireRole("ACCOUNTANT", "ADMIN");
        return ApiResponse.ok(repaymentService.view(repaymentService.verifyPayment(paymentId)));
    }

    /**
     * Reject a recorded payment (proof didn't match the transfer) — Accountant/Admin only. A reason
     * is required (fixed picklist), with an optional free-text note.
     */
    @PostMapping("/{paymentId}/reject")
    public ApiResponse<PaymentView> reject(@PathVariable Long loanId, @PathVariable Long paymentId,
                                           @Valid @RequestBody RejectRepaymentRequest request) {
        requireRole("ACCOUNTANT", "ADMIN");
        return ApiResponse.ok(repaymentService.view(
                repaymentService.rejectPayment(paymentId, request.reason(), request.note())));
    }

    private void requireRole(String... allowed) {
        String role = ActorContext.get().role();
        for (String r : allowed) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new BusinessException("FORBIDDEN_ROLE", "This action requires role " + String.join(" or ", allowed));
    }
}
