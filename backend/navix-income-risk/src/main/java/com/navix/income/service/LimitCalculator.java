package com.navix.income.service;

import com.navix.income.domain.RiskCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Calculates the eligible loan limit from verified monthly salary (integer paise).
 *
 * <p>Product rule: a borrower may take at most <b>25% of monthly salary</b> as a single-repayment
 * loan — a firm cap, floored to the nearest ₹100 (a clean sanction denomination) and never exceeding
 * the <b>₹10,00,000</b> instant ceiling. The risk category may reduce this further but never increase
 * it. Pricing is flat across categories; the category only affects the limit and the depth of checks.
 */
@Service
public class LimitCalculator {

    /** Hard cap: eligible limit is 25% of monthly salary. */
    public static final BigDecimal SALARY_LIMIT_RATE = new BigDecimal("0.25");

    /** Limits are floored to the nearest ₹100 (= 10,000 paise). */
    public static final long LIMIT_ROUNDING_PAISE = 10_000L;

    /** Instant-loan ceiling: the 25%-of-salary limit never exceeds ₹10,00,000 = 100,000,000 paise. */
    public static final long MAX_INSTANT_LOAN_PAISE = 100_000_000L;

    /** The firm 25%-of-salary cap, floored to ₹100 and capped at the ₹10,00,000 instant ceiling. */
    public long eligibleLimitPaise(long monthlySalaryPaise) {
        long quarter = floorToHundredRupees(scale(monthlySalaryPaise, SALARY_LIMIT_RATE));
        return Math.min(quarter, MAX_INSTANT_LOAN_PAISE);
    }

    /**
     * Risk-adjusted limit = base × category factor, floored to ₹100. Never exceeds the 25% base.
     * A keeps the full base; B/C are progressively reduced; D scales to 0.
     *
     * <p>NOTE: this figure feeds {@code RiskAssessment.limitGranted} (underwriting/reporting views)
     * only — the amount actually sanctionable at apply-time is the stored
     * {@code loan_application.eligible_limit}, the un-scaled 25% base from
     * {@link #eligibleLimitPaise}. Risk category historically gates the depth of checks, not the
     * sanctioned amount; wiring this scaled cap into the apply gate is an untaken product decision.
     */
    public long limitForCategory(long monthlySalaryPaise, RiskCategory category) {
        long base = eligibleLimitPaise(monthlySalaryPaise);
        BigDecimal factor = switch (category) {
            case A -> BigDecimal.ONE;
            case B -> new BigDecimal("0.80");
            case C -> new BigDecimal("0.50");
            case D -> BigDecimal.ZERO;
        };
        return floorToHundredRupees(scale(base, factor));
    }

    private static long scale(long paise, BigDecimal rate) {
        return BigDecimal.valueOf(paise).multiply(rate).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private static long floorToHundredRupees(long paise) {
        return Math.floorDiv(paise, LIMIT_ROUNDING_PAISE) * LIMIT_ROUNDING_PAISE;
    }
}
