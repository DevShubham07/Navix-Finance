package com.navix.loan.payu;

import com.navix.common.web.ApiResponse;
import com.navix.loan.payu.PayuSwitchService.GatewayStatus;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Payment-gateway switch. Under {@code /api/staff/**} (staff audience); ADMIN enforced in the service. */
@RestController
@RequiredArgsConstructor
public class PayuSwitchController {

    private final PayuSwitchService service;

    @GetMapping("/api/staff/payment-gateway")
    public ApiResponse<GatewayStatus> status() {
        return ApiResponse.ok(service.status());
    }

    @PutMapping("/api/staff/payment-gateway")
    public ApiResponse<GatewayStatus> setEnabled(@RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(service.setEnabled(Boolean.TRUE.equals(body.get("enabled"))));
    }
}
