package com.navix.loan.dto;

import com.navix.loan.domain.BureauBackfillCohort;
import java.util.Map;

/** DTOs for the ADMIN-only bureau rescore backfill (plana.md Part B §9). */
public final class BureauBackfillDtos {

    private BureauBackfillDtos() {
    }

    /** Dry-run counts — zero provider calls. The reject split tells the operator how many of the
     *  sub-550 rejects are even eligible to reopen (still inside their 90-day cooling-off vs already
     *  past it, where a plain reborrow would have let them back in anyway). */
    public record BackfillPreview(Map<BureauBackfillCohort, Long> counts,
                                  long rejectsInsideBlockWindow, long rejectsOutsideBlockWindow) {
    }

    /** One execute() call — {@code processed} is how many applications this run actually attempted
     *  (already-terminal rows from a prior run are skipped and not counted here). */
    public record BackfillRunSummary(String runId, BureauBackfillCohort cohort, int processed) {
    }
}
