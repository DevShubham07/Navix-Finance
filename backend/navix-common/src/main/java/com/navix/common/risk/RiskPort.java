package com.navix.common.risk;

/**
 * Port for risk grading + the eligible-limit cap, implemented by
 * {@code RiskAdapter} in {@code navix-income-risk}. This is the single authority
 * for the 25%-of-salary cap and the A/B/C/D grade, resolving the historical
 * {@code LoanMath} vs {@code LimitCalculator} duplication — the loan aggregate
 * asks this port instead of recomputing the limit itself.
 *
 * <p>All money is integer paise.
 */
public interface RiskPort {

    /** The firm 25%-of-salary cap, floored to ₹100, in paise. */
    long eligibleLimitPaise(long monthlySalaryPaise);

    /**
     * Grade a borrower from declared salary + (optional) bureau score + employment
     * tenure (months). {@code bureauScore}/{@code employmentMonths} may be null
     * (a sensible default is assumed).
     */
    RiskGrade grade(long monthlySalaryPaise, Integer bureauScore, Integer employmentMonths);

    /** Computed risk grade: category (A/B/C/D), 0–100 score, and the two limit numbers (paise). */
    record RiskGrade(String category, int score, long eligibleLimitPaise, long limitForCategoryPaise) {
    }
}
