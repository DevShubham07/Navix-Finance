package com.navix.collections.service;

import com.navix.collections.domain.DpdBucket;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Computes the live DPD bucket from a loan's due date. Days-past-due and the
 * bucket are ALWAYS derived on read and NEVER stored as the source of truth.
 *
 * <p>Bucket mapping: {@code <=0 -> UPCOMING}, {@code 1-7 -> T0_T7},
 * {@code 8-30 -> T8_T30}, {@code 31-60 -> T30_T60}, {@code 61-90 -> T60_T90},
 * {@code >90 -> T90_PLUS}. Pure logic — has no dependencies and is unit-tested
 * directly with {@code new DpdCalculator()}.
 */
@Service
public class DpdCalculator {

    /**
     * Days past due = {@code max(0, asOf - dueDate)}. Zero when {@code asOf} is on
     * or before the due date (the loan is still UPCOMING / due today).
     *
     * @param dueDate the loan's repayment due date
     * @param asOf    the reference date (typically today)
     * @return the number of days past due, never negative
     */
    public int daysPastDue(LocalDate dueDate, LocalDate asOf) {
        if (asOf == null || dueDate == null || !asOf.isAfter(dueDate)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(dueDate, asOf);
    }

    /**
     * Maps a days-past-due count to its {@link DpdBucket} band.
     *
     * @param dpd days past due (as from {@link #daysPastDue})
     * @return the live DPD bucket
     */
    public DpdBucket bucket(int dpd) {
        if (dpd <= 0) {
            return DpdBucket.UPCOMING;
        }
        if (dpd <= 7) {
            return DpdBucket.T0_T7;
        }
        if (dpd <= 30) {
            return DpdBucket.T8_T30;
        }
        if (dpd <= 60) {
            return DpdBucket.T30_T60;
        }
        if (dpd <= 90) {
            return DpdBucket.T60_T90;
        }
        return DpdBucket.T90_PLUS;
    }

    /**
     * Convenience: the live DPD bucket for a due date as of a reference date.
     *
     * @param dueDate the loan's repayment due date
     * @param asOf    the reference date (typically today)
     * @return the live DPD bucket
     */
    public DpdBucket bucketFor(LocalDate dueDate, LocalDate asOf) {
        return bucket(daysPastDue(dueDate, asOf));
    }
}
