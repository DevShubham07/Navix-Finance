package com.navix.loan.service;

import org.springframework.stereotype.Service;

/**
 * Validates a loan request against eligibility rules before approval.
 *
 * <p>The eligible limit (25% of salary, risk-adjusted) is produced by the income-risk module;
 * this service gates a requested amount against it. All amounts are integer paise.
 */
@Service
public class EligibilityService {

    /**
     * Whether a requested amount is within the eligible limit.
     *
     * @param amountRequestedPaise requested principal, in paise
     * @param eligibleLimitPaise   eligible limit from income-risk, in paise
     * @return true if the amount is positive and does not exceed the limit
     */
    public boolean isEligible(long amountRequestedPaise, long eligibleLimitPaise) {
        return amountRequestedPaise > 0 && amountRequestedPaise <= eligibleLimitPaise;
    }
}
