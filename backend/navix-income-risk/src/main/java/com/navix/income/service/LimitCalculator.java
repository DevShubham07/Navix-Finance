package com.navix.income.service;

import com.navix.income.domain.RiskCategory;
import org.springframework.stereotype.Service;

/**
 * Calculates the eligible loan limit (integer paise).
 *
 * <p>Product rule (instant-loan model): every eligible borrower may draw up to a flat
 * <b>₹10,00,000</b> — the salary-linked 25% rule and the risk-category (A/B/C/D) scaling no longer
 * cap the amount. Salary still drives the due date, not the ceiling. The risk category is retained
 * for underwriting/reporting but does not reduce the sanctionable limit.
 */
@Service
public class LimitCalculator {

    /** Flat instant-loan ceiling: ₹10,00,000 = 100,000,000 paise. */
    public static final long MAX_INSTANT_LOAN_PAISE = 100_000_000L;

    /** The flat instant-loan limit, independent of salary. */
    public long eligibleLimitPaise(long monthlySalaryPaise) {
        return MAX_INSTANT_LOAN_PAISE;
    }

    /**
     * Sanctionable limit — the flat instant-loan ceiling for every category (no risk scaling).
     * Retained for callers that pass a category; salary/category no longer reduce the amount.
     */
    public long limitForCategory(long monthlySalaryPaise, RiskCategory category) {
        return MAX_INSTANT_LOAN_PAISE;
    }
}
