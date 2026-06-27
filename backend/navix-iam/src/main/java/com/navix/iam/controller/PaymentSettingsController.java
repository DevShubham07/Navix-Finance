package com.navix.iam.controller;

import com.navix.common.web.ApiResponse;
import com.navix.iam.dto.PaymentDtos.PaymentSettingsResponse;
import com.navix.iam.dto.PaymentDtos.UpdatePaymentSettingsRequest;
import com.navix.iam.service.PaymentSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company payment block (the payee shown on the borrower repay screen).
 *
 * <ul>
 *   <li>{@code GET} — any authenticated actor (borrower repay + staff admin read).</li>
 *   <li>{@code PUT} — ADMIN only (enforced in {@link PaymentSettingsService}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payment-settings")
@RequiredArgsConstructor
public class PaymentSettingsController {

    private final PaymentSettingsService paymentSettingsService;

    /** Read the current payee (presigned asset URLs included when uploaded). */
    @GetMapping
    public ApiResponse<PaymentSettingsResponse> get() {
        return ApiResponse.ok(paymentSettingsService.get());
    }

    /** Update the payee (ADMIN only). */
    @PutMapping
    public ApiResponse<PaymentSettingsResponse> update(@RequestBody UpdatePaymentSettingsRequest request) {
        return ApiResponse.ok(paymentSettingsService.update(request));
    }
}
