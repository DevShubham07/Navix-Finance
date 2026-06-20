package com.navix.loan.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Validates a loan request against eligibility rules before approval.
 *
 * <p>Checks include: requested amount within the eligible limit (25% of
 * salary), salary-day alignment, and risk-category gating.
 *
 * TODO: depend on income-risk outputs (eligible limit + category) once the
 * cross-module contract is finalized; implement the checks below.
 */
@Service
public class EligibilityService {

    /**
     * Check whether a requested amount is eligible for the given limit.
     *
     * TODO: implement full rule set (limit, salary alignment, category).
     *
     * @param amountRequested requested principal
     * @param eligibleLimit   eligible limit from income-risk
     * @return true if eligible
     */
    public boolean isEligible(BigDecimal amountRequested, BigDecimal eligibleLimit) {
        // TODO: implement eligibility checks.
        throw new UnsupportedOperationException("EligibilityService.isEligible not implemented yet");
    }
}
