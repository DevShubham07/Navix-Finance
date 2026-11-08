package com.navix.loan.payu;

import com.navix.common.web.ApiResponse;
import com.navix.loan.payu.PayuService.Checkout;
import com.navix.loan.payu.PayuService.OrderView;
import com.navix.loan.payu.PayuService.State;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Borrower-facing PayU repayment endpoints. BORROWER only, own loan only (enforced in the service). */
@RestController
@RequestMapping("/api/loan/{loanId}/payu")
@RequiredArgsConstructor
public class PayuController {

    public record CreateOrderRequest(@Positive long amountPaise) {
    }

    private final PayuService payu;

    @GetMapping("/state")
    public ApiResponse<State> state(@PathVariable Long loanId,
                                    @RequestParam(required = false) Long amountPaise) {
        return ApiResponse.ok(payu.state(loanId, amountPaise));
    }

    @PostMapping("/orders")
    public ApiResponse<Checkout> create(@PathVariable Long loanId, @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(payu.createOrder(loanId, request.amountPaise()));
    }

    @GetMapping("/orders/{txnid}")
    public ApiResponse<OrderView> order(@PathVariable Long loanId, @PathVariable String txnid) {
        return ApiResponse.ok(payu.order(loanId, txnid));
    }
}
