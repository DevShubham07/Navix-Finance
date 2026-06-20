package com.navix.income.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Calculates the eligible loan limit from verified monthly salary.
 *
 * <p>Product rule: a borrower may take at most 25% of their monthly salary
 * as a single-repayment loan. The risk category may reduce this further but
 * never increase it; this calculator enforces the hard cap.
 *
 * <pre>
 *   eligibleLimit = floor(monthlySalary * 0.25)
 * </pre>
 */
@Service
public class LimitCalculator {

    /** Hard cap: eligible limit is 25% of monthly salary. */
    public static final BigDecimal SALARY_LIMIT_RATE = new BigDecimal("0.25");

    /**
     * Compute the eligible limit (hard cap) for a given monthly salary.
     *
     * <p>{@code eligibleLimit = floor(monthlySalary * 0.25)}.
     *
     * TODO: implement floor rounding to whole rupees and apply any
     * category-based reduction supplied by the caller.
     *
     * @param monthlySalary verified gross monthly salary
     * @return floored eligible limit
     */
    public BigDecimal eligibleLimit(BigDecimal monthlySalary) {
        // TODO: return monthlySalary.multiply(SALARY_LIMIT_RATE) floored to whole units.
        throw new UnsupportedOperationException("LimitCalculator.eligibleLimit not implemented yet");
    }
}
