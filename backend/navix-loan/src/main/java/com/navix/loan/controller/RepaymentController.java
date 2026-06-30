package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanDtos.PaymentView;
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
        return ApiResponse.ok(PaymentView.of(repaymentService.recordPayment(
                loanId, request.amountPaise(), request.method(), request.txnRef(),
                request.proofUrl(), request.paidOn())));
    }

    /** List repayments recorded against the loan. */
    @GetMapping
    public ApiResponse<List<PaymentView>> list(@PathVariable Long loanId) {
        return ApiResponse.ok(repaymentService.listPayments(loanId).stream()
                .map(PaymentView::of)
                .toList());
    }

    /** Confirm proof for a recorded payment (maker-checker: typically an accountant/ops action). */
    @PostMapping("/{paymentId}/verify")
    public ApiResponse<PaymentView> verify(@PathVariable Long loanId, @PathVariable Long paymentId) {
        return ApiResponse.ok(PaymentView.of(repaymentService.verifyPayment(paymentId)));
    }

    /** Reject a recorded payment (proof didn't match the transfer). Accountant/Admin only. */
    @PostMapping("/{paymentId}/reject")
    public ApiResponse<PaymentView> reject(@PathVariable Long loanId, @PathVariable Long paymentId) {
        requireRole("ACCOUNTANT", "ADMIN");
        return ApiResponse.ok(PaymentView.of(repaymentService.rejectPayment(paymentId)));
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
