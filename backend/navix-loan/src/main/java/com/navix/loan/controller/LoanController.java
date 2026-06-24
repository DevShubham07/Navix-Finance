package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.OutstandingView;
import com.navix.loan.service.LoanService;
import com.navix.loan.service.RepaymentService;
import java.time.LocalDate;
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

    @GetMapping("/{loanId}")
    public ApiResponse<LoanView> getLoan(@PathVariable Long loanId) {
        return ApiResponse.ok(LoanView.of(loanService.getLoan(loanId)));
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
