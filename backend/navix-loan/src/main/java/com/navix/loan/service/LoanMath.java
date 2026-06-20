package com.navix.loan.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Pure money-math for the single-repayment loan product.
 *
 * <p>All amounts are {@link BigDecimal}. Pricing is flat across risk
 * categories. Worked example for a principal of 10,000:
 * <pre>
 *   processingFee  = 10000 * 0.10        = 1000.00
 *   gst            = 1000  * 0.18        =  180.00
 *   netDisbursed   = 10000 - 1000 - 180  = 8820.00
 *   interest (30d) = 10000 * 0.01 * 30   = 3000.00
 *   totalRepayable = 10000 + 3000        = 13000.00   (principal + interest)
 * </pre>
 * Note: processing fee + GST are taken up front (reduce net disbursed); they
 * are not part of {@code totalRepayable}, which is principal + accrued interest.
 *
 * TODO: confirm rounding mode (HALF_UP to 2dp expected) and finalize whether
 * fee/GST ever feed into repayable; implement bodies below.
 */
@Service
public class LoanMath {

    /** Up-front processing fee rate: 10% of principal. */
    public static final BigDecimal PROCESSING_FEE_RATE = new BigDecimal("0.10");

    /** GST rate applied on the processing fee: 18%. */
    public static final BigDecimal GST_RATE = new BigDecimal("0.18");

    /** Interest accrued per day: 1% of principal. */
    public static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.01");

    /** Late penalty per day past due: 2% of outstanding. */
    public static final BigDecimal LATE_PENALTY_RATE = new BigDecimal("0.02");

    /** Late penalty accrues for at most this many days, then collections. */
    public static final int LATE_PENALTY_CAP_DAYS = 30;

    /**
     * Up-front processing fee = principal * {@link #PROCESSING_FEE_RATE}.
     *
     * @param principal sanctioned principal
     * @return processing fee
     */
    public BigDecimal processingFee(BigDecimal principal) {
        // TODO: implement: principal * PROCESSING_FEE_RATE (HALF_UP, 2dp).
        throw new UnsupportedOperationException("LoanMath.processingFee not implemented yet");
    }

    /**
     * GST on the processing fee = processingFee * {@link #GST_RATE}.
     *
     * @param principal sanctioned principal
     * @return GST amount on the fee
     */
    public BigDecimal gst(BigDecimal principal) {
        // TODO: implement: processingFee(principal) * GST_RATE (HALF_UP, 2dp).
        throw new UnsupportedOperationException("LoanMath.gst not implemented yet");
    }

    /**
     * Net amount credited = principal - processingFee - gst.
     *
     * @param principal sanctioned principal
     * @return net disbursed amount
     */
    public BigDecimal netDisbursed(BigDecimal principal) {
        // TODO: implement: principal - processingFee(principal) - gst(principal).
        throw new UnsupportedOperationException("LoanMath.netDisbursed not implemented yet");
    }

    /**
     * Interest for a number of days = principal * {@link #DAILY_INTEREST_RATE} * days.
     *
     * @param principal sanctioned principal
     * @param days      number of days interest accrues
     * @return accrued interest
     */
    public BigDecimal interestForDays(BigDecimal principal, int days) {
        // TODO: implement: principal * DAILY_INTEREST_RATE * days (HALF_UP, 2dp).
        throw new UnsupportedOperationException("LoanMath.interestForDays not implemented yet");
    }

    /**
     * Total repayable on the due date = principal + interest for the loan term.
     *
     * @param principal sanctioned principal
     * @param days      number of days from disbursement to repayment
     * @return total amount repayable
     */
    public BigDecimal totalRepayable(BigDecimal principal, int days) {
        // TODO: implement: principal + interestForDays(principal, days).
        throw new UnsupportedOperationException("LoanMath.totalRepayable not implemented yet");
    }

    /**
     * Determine the single-repayment due date: the borrower's next salary
     * credit, which must be the last salary credit falling within ~40 days
     * of disbursement.
     *
     * @param disbursedOn     date of disbursement
     * @param salaryCreditDay day of month salary is credited (1-31)
     * @return computed due date
     */
    public LocalDate dueDateFromSalary(LocalDate disbursedOn, int salaryCreditDay) {
        // TODO: implement: find the salary-credit date that is the last one
        // within ~40 days of disbursedOn; handle month-length edge cases.
        throw new UnsupportedOperationException("LoanMath.dueDateFromSalary not implemented yet");
    }
}
