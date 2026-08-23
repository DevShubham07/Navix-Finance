package com.navix.loan.domain;

/**
 * The four cohorts the bureau rescore backfill (plana.md Part B §8) re-pulls, each selected by
 * application status (or, for {@link #REJECTS}, status + rejection reason). {@code ACTIVE}/
 * {@code OVERDUE} loans and {@code MANUAL}/{@code SELF_EMPLOYED} rejections are deliberately excluded
 * — see {@code BureauBackfillService}.
 */
public enum BureauBackfillCohort {
    /** {@code REJECTED} with an {@code application_rejection.reason_code = 'LOW_BUREAU_SCORE'} row. */
    REJECTS,
    /** {@code CREDIT_EXEC_PENDING} — refresh only. */
    CREDIT_REVIEW,
    /** {@code REVIEW_PENDING}, {@code KYC_PENDING} — refresh only. */
    PENDING_REVIEW,
    /** {@code KYC_APPROVED}, {@code PRE_APPROVED}, {@code SANCTIONED} — refresh only. */
    APPROVED
}
