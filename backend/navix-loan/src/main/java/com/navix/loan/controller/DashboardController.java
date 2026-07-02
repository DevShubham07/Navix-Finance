package com.navix.loan.controller;

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
        return ApiResponse.ok(dashboardService.trends(days));
    }
}
