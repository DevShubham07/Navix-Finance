package com.navix.collections.domain;

/**
 * Days-Past-Due bucket for a loan in collections. Computed LIVE from the due
 * date (see DpdCalculator) and never persisted on the case.
 */
public enum DpdBucket {
    /** Not yet due. */
    UPCOMING,
    /** 0-7 days past due. */
    T0_T7,
    /** 8-30 days past due. */
    T8_T30,
    /** 31-60 days past due. */
    T30_T60,
    /** 61-90 days past due. */
    T60_T90,
    /** 90+ days past due. */
    T90_PLUS
}
