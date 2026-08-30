package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.DashboardDtos.TrendResponse;
import com.navix.loan.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff dashboard analytics — day-by-day trends derived from existing timestamps. */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** Daily applications / disbursals / repayments over the last {@code days} (default 30). */
    @GetMapping("/trends")
    public ApiResponse<TrendResponse> trends(@RequestParam(defaultValue = "30") int days) {
        requireStaff();
        return ApiResponse.ok(dashboardService.trends(days));
    }

    /**
     * Staff-only gate: blocks borrower and anonymous tokens, and explicitly blocks DSA (which
     * satisfies {@code hasRole("STAFF")} at the audience level). DSAs cannot view company-wide
     * aggregate counts.
     */
    private void requireStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
        if ("DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view aggregate dashboard");
        }
    }
}
