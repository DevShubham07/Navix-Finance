package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.ReferralPayoutStatus;
import com.navix.loan.dto.ReferralDtos.ApplyCodeRequest;
import com.navix.loan.dto.ReferralDtos.ApplyCodeResult;
import com.navix.loan.dto.ReferralDtos.ExpenseSummaryView;
import com.navix.loan.dto.ReferralDtos.MyReferralView;
import com.navix.loan.dto.ReferralDtos.PayPayoutRequest;
import com.navix.loan.dto.ReferralDtos.PayoutView;
import com.navix.loan.dto.ReferralDtos.ValidateCodeView;
import com.navix.loan.service.ReferralService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refer-a-friend endpoints. Borrower actions ({@code /me}, {@code /apply}, {@code /validate}) and
 * Disbursement-Head payout management ({@code /payouts*}, {@code /expenses}) are role-gated in
 * {@link ReferralService} (non-eligible role → {@code FORBIDDEN_ROLE}, HTTP 422).
 */
@RestController
@RequestMapping("/api/referral")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    // ---- borrower ------------------------------------------------------------------

    /** The caller's own referral code + reward + earnings (code minted lazily on first read). */
    @GetMapping("/me")
    public ApiResponse<MyReferralView> me() {
        return ApiResponse.ok(referralService.myReferral());
    }

    /** Apply a referral code to the calling borrower (best-effort from the signup UI). */
    @PostMapping("/apply")
    public ApiResponse<ApplyCodeResult> apply(@Valid @RequestBody ApplyCodeRequest request) {
        return ApiResponse.ok(referralService.applyCode(request.code()));
    }

    /** Preview a code (live signup feedback) — never errors; returns {@code valid=false} on any issue. */
    @GetMapping("/validate")
    public ApiResponse<ValidateCodeView> validate(@RequestParam("code") String code) {
        return ApiResponse.ok(referralService.validate(code));
    }

    // ---- staff (Disbursement Head / Admin) -----------------------------------------

    /** Payout queue / expense list. {@code status=PENDING|PAID} filters; omitted → all (newest first). */
    @GetMapping("/payouts")
    public ApiResponse<List<PayoutView>> payouts(@RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(referralService.listPayouts(parseStatus(status)));
    }

    /** Mark a payout paid, logging the transaction id. */
    @PostMapping("/payouts/{id}/pay")
    public ApiResponse<PayoutView> pay(@PathVariable Long id, @Valid @RequestBody PayPayoutRequest request) {
        return ApiResponse.ok(referralService.payPayout(id, request.txnRef()));
    }

    /** Totals for the referral-expense dashboard. */
    @GetMapping("/expenses")
    public ApiResponse<ExpenseSummaryView> expenses() {
        return ApiResponse.ok(referralService.expenseSummary());
    }

    /** Lenient status parse — only PENDING/PAID are recognized; anything else means "all". */
    private static ReferralPayoutStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReferralPayoutStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
