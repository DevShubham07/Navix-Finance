package com.navix.loan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Pure money-math for the salary-linked single-repayment loan product.
 *
 * <p><b>Money is integer paise everywhere</b> (1 rupee = 100 paise). Every amount in and
 * out of this class is a {@code long} number of paise; there is no floating-point money.
 * Rounding to a whole paisa is always {@link RoundingMode#HALF_UP}. This class is the
 * backend source of truth for loan economics and mirrors
 * {@code frontend/src/lib/calc/loan-math.ts}.
 *
 * <p>Pricing is flat across risk categories. Worked example for a principal of ₹10,000
 * (= 1_000_000 paise):
 * <pre>
 *   processingFee  = 1_000_000 * 0.10          =   100_000   (₹1,000)
 *   gst            =   100_000 * 0.18           =    18_000   (₹180)
 *   netDisbursed   = 1_000_000 - 100_000 - 18_000 = 882_000   (₹8,820)
 *   interest (30d) = 1_000_000 * 0.01 * 30       =   300_000   (₹3,000)
 *   totalRepayable = 1_000_000 + 300_000         = 1_300_000   (₹13,000)
 * </pre>
 * Processing fee + GST are taken up front (they reduce {@code netDisbursed}); they are
 * <i>not</i> part of {@code totalRepayable}, which is principal + accrued interest.
 */
@Service
public class LoanMath {

    /** Up-front processing fee rate: 10% of principal. */
    public static final BigDecimal PROCESSING_FEE_RATE = new BigDecimal("0.10");

    /** GST rate applied on the processing fee: 18%. */
    public static final BigDecimal GST_RATE = new BigDecimal("0.18");

    /** Interest accrued per day: 1% of principal. */
    public static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.01");

    /** Late penalty per day past due: 2% of principal. */
    public static final BigDecimal LATE_PENALTY_RATE = new BigDecimal("0.02");

    /** Late penalty accrues for at most this many days, then collections. */
    public static final int LATE_PENALTY_CAP_DAYS = 30;

    /** Flat instant-loan ceiling: ₹10,00,000 = 100,000,000 paise (salary no longer caps the amount). */
    public static final long MAX_INSTANT_LOAN_PAISE = 100_000_000L;

    /** Eligible limit is rounded down to the nearest ₹100 (= 10,000 paise). */
    public static final long LIMIT_ROUNDING_PAISE = 10_000L;

    /** Maximum loan term: the due date must fall within this many days of disbursement. */
    public static final int MAX_TERM_DAYS = 40;

    /** Fixed loan term: 30-day single-repayment bullet loan, due = disbursed + 30d (dfd.md D10). */
    public static final int TERM_DAYS = 30;

    /** Grace after the due date before any penalty accrues (paying the day after salary is free). */
    public static final int SALARY_GRACE_DAYS = 1;

    /** Floor on a sanctioned advance: ₹1,000 = 100,000 paise. */
    public static final long MIN_LOAN_PAISE = 100_000L;

    /** Up-front processing fee = round(principal × 10%). */
    public long processingFeePaise(long principalPaise) {
        return roundPaise(BigDecimal.valueOf(principalPaise).multiply(PROCESSING_FEE_RATE));
    }

    /** GST on the processing fee = round(fee × 18%). */
    public long gstPaise(long principalPaise) {
        return roundPaise(BigDecimal.valueOf(processingFeePaise(principalPaise)).multiply(GST_RATE));
    }

    /** Net amount credited to the borrower = principal − fee − GST. */
    public long netDisbursedPaise(long principalPaise) {
        return principalPaise - processingFeePaise(principalPaise) - gstPaise(principalPaise);
    }

    /** Interest accrued over {@code days} = round(principal × 1%/day × days). Negative days clamp to 0. */
    public long interestPaise(long principalPaise, int days) {
        int d = Math.max(0, days);
        return roundPaise(BigDecimal.valueOf(principalPaise)
                .multiply(DAILY_INTEREST_RATE)
                .multiply(BigDecimal.valueOf(d)));
    }

    /** Total repayable after {@code days} held = principal + accrued interest (fee/GST excluded). */
    public long totalRepayablePaise(long principalPaise, int days) {
        return principalPaise + interestPaise(principalPaise, days);
    }

    /**
     * Late penalty for {@code daysLate} = round(principal × 2%/day × min(daysLate, 30)).
     * Non-positive {@code daysLate} yields 0.
     */
    public long latePenaltyPaise(long principalPaise, int daysLate) {
        int cappedDays = Math.min(Math.max(0, daysLate), LATE_PENALTY_CAP_DAYS);
        return roundPaise(BigDecimal.valueOf(principalPaise)
                .multiply(LATE_PENALTY_RATE)
                .multiply(BigDecimal.valueOf(cappedDays)));
    }

    /**
     * Eligible loan limit — a flat ₹10,00,000 for every eligible borrower (instant-loan model).
     * Salary no longer caps the amount; it still drives the due date.
     *
     * @param monthlySalaryPaise gross monthly salary in paise (unused; kept for signature stability)
     * @return the flat sanctionable limit in paise
     */
    public long eligibleLimitPaise(long monthlySalaryPaise) {
        return MAX_INSTANT_LOAN_PAISE;
    }

    /**
     * Canonical compute-on-read outstanding balance as of some day.
     *
     * <p>{@code outstanding = principal + interest(daysHeld) + penalty(daysLate) − payments}.
     * Prepayment falls out naturally: pass the actual days held so interest is charged only to
     * the day paid. {@code daysLate} is 0 until the due date (the 1-day grace is applied by the
     * caller, e.g. paying the day after salary passes {@code daysLate = 0}).
     *
     * @param principalPaise principal in paise
     * @param daysHeld       whole days from disbursement to the as-of date (interest accrues)
     * @param daysLate       whole days past the due date after grace (penalty accrues, capped)
     * @param paymentsPaise  sum of verified payments already made, in paise
     * @return remaining amount owed in paise (never negative)
     */
    public long outstandingPaise(long principalPaise, int daysHeld, int daysLate, long paymentsPaise) {
        long gross = totalRepayablePaise(principalPaise, daysHeld)
                + latePenaltyPaise(principalPaise, daysLate);
        return Math.max(0L, gross - paymentsPaise);
    }

    /**
     * Whole days a loan is past due as of {@code asOf} (0 before/on the due date).
     * Bucketing into DPD buckets lives in the collections module.
     */
    public int daysPastDue(LocalDate dueDate, LocalDate asOf) {
        long days = dueDate.until(asOf, java.time.temporal.ChronoUnit.DAYS);
        return (int) Math.max(0L, days);
    }

    /**
     * Single-repayment due date: the <b>latest</b> salary-credit date that is strictly after
     * disbursement and falls within {@link #MAX_TERM_DAYS} days of it — i.e. the last salary the
     * borrower receives inside the 40-day window (maximising tenure up to 40 days).
     *
     * <p>The salary day is clamped to each month's length (e.g. day 31 → 30 Apr, 28/29 Feb).
     * Within any 40-day window there is always at least one monthly salary date.
     *
     * @param disbursedOn     date of disbursement
     * @param salaryCreditDay day of month salary is credited (1–31)
     * @return the computed due date
     */
    public LocalDate dueDateFromSalary(LocalDate disbursedOn, int salaryCreditDay) {
        LocalDate windowEnd = disbursedOn.plusDays(MAX_TERM_DAYS);
        LocalDate best = null;
        // Walk salary dates from the disbursement month through the window-end month.
        for (LocalDate month = disbursedOn.withDayOfMonth(1);
                !month.isAfter(windowEnd.withDayOfMonth(1));
                month = month.plusMonths(1)) {
            LocalDate salaryDate = salaryDateInMonth(month, salaryCreditDay);
            if (salaryDate.isAfter(disbursedOn) && !salaryDate.isAfter(windowEnd)) {
                if (best == null || salaryDate.isAfter(best)) {
                    best = salaryDate;
                }
            }
        }
        // Fallback (should not happen for a monthly salary, but keep it total): the first salary
        // strictly after disbursement, even if it exceeds the window.
        if (best == null) {
            LocalDate salaryThisMonth = salaryDateInMonth(disbursedOn, salaryCreditDay);
            best = salaryThisMonth.isAfter(disbursedOn)
                    ? salaryThisMonth
                    : salaryDateInMonth(disbursedOn.plusMonths(1), salaryCreditDay);
        }
        return best;
    }

    /** Clamp a salary day-of-month to a valid date within the month of {@code anyDayInMonth}. */
    private static LocalDate salaryDateInMonth(LocalDate anyDayInMonth, int salaryCreditDay) {
        int lastDay = anyDayInMonth.lengthOfMonth();
        int day = Math.min(Math.max(1, salaryCreditDay), lastDay);
        return anyDayInMonth.withDayOfMonth(day);
    }

    /** Round fractional paise to a whole paisa, HALF_UP. */
    private static long roundPaise(BigDecimal paise) {
        return paise.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
