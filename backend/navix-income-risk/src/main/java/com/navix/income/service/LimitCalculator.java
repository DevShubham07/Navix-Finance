package com.navix.income.service;

import com.navix.income.domain.RiskCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Calculates the eligible loan limit from verified monthly salary (integer paise).
 *
 * <p>Product rule: a borrower may take at most <b>25% of monthly salary</b> as a single-repayment
 * loan — a firm cap. The risk category may reduce this further but never increase it. Amounts are
 * floored to the nearest ₹100 (a clean sanction denomination). Pricing is flat across categories;
 * the category only affects the limit and the depth of checks.
 */
@Service
public class LimitCalculator {

    /** Hard cap: eligible limit is 25% of monthly salary. */
    public static final BigDecimal SALARY_LIMIT_RATE = new BigDecimal("0.25");

    /** Limits are floored to the nearest ₹100 (= 10,000 paise). */
    public static final long LIMIT_ROUNDING_PAISE = 10_000L;

    /** The firm 25%-of-salary cap, floored to ₹100, in paise. */
    public long eligibleLimitPaise(long monthlySalaryPaise) {
        return floorToHundredRupees(scale(monthlySalaryPaise, SALARY_LIMIT_RATE));
    }

    /**
     * Risk-adjusted limit = cap × category factor, floored to ₹100. Never exceeds the 25% cap.
     * A keeps the full cap; B/C are progressively reduced; D is declined (0).
     */
    public long limitForCategory(long monthlySalaryPaise, RiskCategory category) {
        long cap = eligibleLimitPaise(monthlySalaryPaise);
        BigDecimal factor = switch (category) {
            case A -> BigDecimal.ONE;
            case B -> new BigDecimal("0.80");
            case C -> new BigDecimal("0.50");
            case D -> BigDecimal.ZERO;
        };
        return floorToHundredRupees(scale(cap, factor));
    }

    private static long scale(long paise, BigDecimal rate) {
        return BigDecimal.valueOf(paise).multiply(rate).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private static long floorToHundredRupees(long paise) {
        return Math.floorDiv(paise, LIMIT_ROUNDING_PAISE) * LIMIT_ROUNDING_PAISE;
    }
}
