package com.navix.income.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for income profiles and risk assessment.
 *
 * TODO: define request/response DTOs and wire to
 * {@code RiskScoringService} / {@code LimitCalculator}.
 */
@RestController
@RequestMapping("/api/income")
public class IncomeController {

    /**
     * Fetch the income + risk view for an applicant.
     *
     * TODO: implement.
     */
    @GetMapping("/{applicantId}")
    public Object getIncomeProfile(@PathVariable Long applicantId) {
        // TODO: return income profile + latest risk assessment + eligible limit.
        throw new UnsupportedOperationException("IncomeController.getIncomeProfile not implemented yet");
    }
}
