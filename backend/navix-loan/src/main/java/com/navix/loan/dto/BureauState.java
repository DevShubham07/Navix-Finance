package com.navix.loan.dto;

/**
 * Whether the bureau pull ran for an application, and what it found. Derived from the BUREAU
 * {@code application_verification} row — never from application status (pulls happen while the
 * application is still nominally DRAFT, before {@code submit-kyc}).
 */
public enum BureauState {
    /** No completed pull: no BUREAU row, or the row is REVIEW (consent deferred / provider error). */
    NOT_FETCHED,
    /** Pull completed (status PASS) and the provider reported no record for this identity. */
    NO_RECORD,
    /** Pull completed and returned a record. NOTE: creditScore/starRating can still be null here
     *  (thin file with no computable rating) — callers must handle that combination too. */
    FOUND
}
