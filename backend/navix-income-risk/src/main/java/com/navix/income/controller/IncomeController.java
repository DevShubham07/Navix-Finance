package com.navix.income.controller;

import com.navix.common.web.ApiResponse;
import com.navix.income.dto.IncomeDtos.IncomeView;
import com.navix.income.dto.IncomeDtos.ProfileRequest;
import com.navix.income.dto.IncomeDtos.ProfileView;
import com.navix.income.dto.IncomeDtos.RiskView;
import com.navix.income.service.IncomeService;
import com.navix.income.service.RiskScoringService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Income profile + risk/limit endpoints. */
@RestController
@RequestMapping("/api/income")
@RequiredArgsConstructor
public class IncomeController {

    private final IncomeService incomeService;
    private final RiskScoringService riskScoringService;

    /** Income profile + latest risk assessment + current eligible limit. */
    @GetMapping("/{customerId}")
    public ApiResponse<IncomeView> getIncomeProfile(@PathVariable Long customerId) {
        return ApiResponse.ok(incomeService.view(customerId));
    }

    /** Create or update the customer's verified income profile. */
    @PostMapping("/{customerId}/profile")
    public ApiResponse<ProfileView> saveProfile(@PathVariable Long customerId,
                                                @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(ProfileView.of(incomeService.saveProfile(customerId,
                request.monthlySalaryPaise(), request.salaryCreditDay(), request.employer(),
                request.uanTenure())));
    }

    /** Run the risk engine for the customer and persist a fresh assessment. */
    @PostMapping("/{customerId}/assess")
    public ApiResponse<RiskView> assess(@PathVariable Long customerId) {
        return ApiResponse.ok(RiskView.of(riskScoringService.assess(customerId)));
    }
}
