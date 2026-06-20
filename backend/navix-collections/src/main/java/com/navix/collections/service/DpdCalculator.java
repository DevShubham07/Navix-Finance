package com.navix.collections.service;

import com.navix.collections.domain.DpdBucket;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Computes the live DPD bucket from a loan's due date. The bucket is ALWAYS
 * derived on read and NEVER stored as the source of truth.
 *
 * Bucket mapping: UPCOMING (not due), T0_T7 (0-7), T8_T30 (8-30),
 * T30_T60 (31-60), T60_T90 (61-90), T90_PLUS (90+).
 */
@Service
public class DpdCalculator {

    /**
     * @param dueDate the loan's repayment due date
     * @param asOf    the reference date (typically today)
     * @return the live DPD bucket
     * TODO: compute days-past-due = asOf - dueDate and map to the bucket.
     */
    public DpdBucket bucketFor(LocalDate dueDate, LocalDate asOf) {
        throw new UnsupportedOperationException("TODO: implement live DPD bucket computation");
    }
}
