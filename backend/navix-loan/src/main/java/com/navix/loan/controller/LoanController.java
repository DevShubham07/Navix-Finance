package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.OutstandingView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.service.LoanService;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.TransactionService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disbursed-loan read endpoints (the loan ledger). Application creation/lifecycle lives under
 * {@code /api/applications}; repayments under {@code /api/loan/{loanId}/repayments}.
 */
@RestController
@RequestMapping("/api/loan")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loanService;
    private final RepaymentService repaymentService;
    private final TransactionService transactionService;

    @GetMapping("/{loanId}")
    public ApiResponse<LoanView> getLoan(@PathVariable Long loanId) {
        var loan = loanService.getLoan(loanId);
        // Single source of truth for "amount owed": the penalty/prepayment-aware balance, and an
        // effective status so a past-due ACTIVE loan reads as OVERDUE (matches the repay page).
        long owed = repaymentService.outstandingAsOf(loanId, null);
        return ApiResponse.ok(LoanView.of(loan, owed, loan.effectiveStatus(LocalDate.now())));
    }

    /**
     * Accountant queue: repayments awaiting proof verification, across all loans. A literal path so
     * it is selected over {@code /{loanId}} (PathPattern ranks literal segments above captures).
     */
    @GetMapping("/pending-repayments")
    public ApiResponse<List<PaymentView>> pendingRepayments() {
        return ApiResponse.ok(repaymentService.listPending().stream().map(PaymentView::of).toList());
    }

    /**
     * Accountant transactions ledger: all money movement (OUTGOING disbursals + INCOMING
     * repayments), company-wide, optionally filtered by {@code direction} and a free-text
     * {@code q} (borrower name / mobile / loan id). Literal path → ranks above {@code /{loanId}}.
     */
    @GetMapping("/transactions")
    public ApiResponse<List<TransactionView>> transactions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String direction) {
        return ApiResponse.ok(transactionService.listTransactions(q, direction));
    }

    /** Authoritative compute-on-read balance (prepayment + penalty aware). */
    @GetMapping("/{loanId}/outstanding")
    public ApiResponse<OutstandingView> outstanding(
            @PathVariable Long loanId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        long out = repaymentService.outstandingAsOf(loanId, asOf);
        return ApiResponse.ok(new OutstandingView(loanId, asOf != null ? asOf : LocalDate.now(), out));
    }
}
