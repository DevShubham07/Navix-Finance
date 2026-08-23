package com.navix.loan.domain;

/** Per-application result of one bureau-backfill attempt ({@code bureau_backfill_row.outcome}). */
public enum BureauBackfillOutcome {
    /** REJECTS cohort, new score ≥ floor: the application was reopened DRAFT → KYC_PENDING. */
    REOPENED,
    /** A live cohort's score/brief/PDF were refreshed in place; the application was never touched. */
    REFRESHED,
    /** REJECTS cohort, new score still under the floor: untouched, cooling-off clock not reset. */
    STILL_BELOW,
    /** The pull succeeded but yielded no facts (thin-file/no-hit) — {@code CreditBriefService.generate}
     *  no-op'd, so there is no fresh brief to show for this attempt. */
    NO_BRIEF,
    /** The report's own PAN/DOB didn't match the verified KYC identity — never reopened or refreshed. */
    MISMATCH_REVIEW,
    /** CRIF gated the (real, existing) report behind a knowledge-based-auth question — no score to act
     *  on. Treated as a completed attempt, NOT retried, so it can't loop the same billable call. */
    KBA_REQUIRED,
    /** The attempt failed at {@code failed_step}; the only outcome a re-run retries. */
    FAILED,
    /** A re-run found this application already terminally processed, or (REJECTS only) the reopen
     *  target was no longer eligible (not REJECTED, or its rejecting transition wasn't from DRAFT). */
    SKIPPED
}
