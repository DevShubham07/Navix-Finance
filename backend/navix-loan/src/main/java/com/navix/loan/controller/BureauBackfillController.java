package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.BureauBackfillCohort;
import com.navix.loan.dto.BureauBackfillDtos.BackfillPreview;
import com.navix.loan.dto.BureauBackfillDtos.BackfillRunSummary;
import com.navix.loan.dto.BureauBackfillDtos.SweepSummary;
import com.navix.loan.service.BureauBackfillService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only bureau rescore backfill (plana.md Part B). {@code /api/admin/**} is already gated to
 * {@code ROLE_STAFF} in {@code SecurityConfig}; {@link BureauBackfillService} enforces its own
 * {@code requireAdmin()} on every method, exactly like the rest of the {@code /api/admin/**} surface.
 * No admin screen (product decision) — the {@code bureau_backfill_row} table is read by SQL.
 */
@RestController
@RequestMapping("/api/admin/bureau-backfill")
@RequiredArgsConstructor
public class BureauBackfillController {

    private final BureauBackfillService service;

    /** Dry run: counts per cohort, zero provider calls. Approve this before spending anything. */
    @GetMapping("/preview")
    public ApiResponse<BackfillPreview> preview() {
        return ApiResponse.ok(service.preview());
    }

    /**
     * {@code limit} is validated against {@link BureauBackfillService#MAX_ROWS_PER_RUN}.
     *
     * <p>Optional {@code ids} narrows the run to exactly those applications, bypassing the cohort's
     * status filter and the already-processed skip; {@code cohort} then only selects what happens
     * after the pull. Use it to re-pull a handful of named files without walking a whole cohort.
     */
    @PostMapping("/execute")
    public ApiResponse<BackfillRunSummary> execute(@RequestParam BureauBackfillCohort cohort,
                                                    @RequestParam int limit,
                                                    @RequestParam(required = false) List<Long> ids) {
        return ApiResponse.ok(service.execute(cohort, limit, ids == null ? List.of() : ids));
    }

    /**
     * Reject the live applications already sitting on a sub-floor score. Makes <b>no</b> provider call.
     * Defaults to {@code dryRun=true} — this rejects real borrowers and starts a 90-day cooling-off,
     * so the destructive form has to be asked for explicitly.
     */
    @PostMapping("/reject-sub-floor")
    public ApiResponse<SweepSummary> rejectSubFloor(@RequestParam int limit,
                                                    @RequestParam(defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok(service.rejectSubFloor(limit, dryRun));
    }
}
