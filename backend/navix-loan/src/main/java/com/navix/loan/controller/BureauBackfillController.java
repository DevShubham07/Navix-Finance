package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.BureauBackfillCohort;
import com.navix.loan.dto.BureauBackfillDtos.BackfillPreview;
import com.navix.loan.dto.BureauBackfillDtos.BackfillRunSummary;
import com.navix.loan.service.BureauBackfillService;
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

    /** {@code limit} is validated against {@link BureauBackfillService#MAX_ROWS_PER_RUN}. */
    @PostMapping("/execute")
    public ApiResponse<BackfillRunSummary> execute(@RequestParam BureauBackfillCohort cohort,
                                                    @RequestParam int limit) {
        return ApiResponse.ok(service.execute(cohort, limit));
    }
}
