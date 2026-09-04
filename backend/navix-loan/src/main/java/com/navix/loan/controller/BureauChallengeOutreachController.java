package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.BureauChallengeOutreachService;
import com.navix.loan.service.BureauChallengeOutreachService.OutreachSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only outreach to borrowers parked behind an unanswered bureau KBA question. Same conventions
 * as {@link BureauBackfillController}: {@code /api/admin/**} is gated to {@code ROLE_STAFF} in
 * {@code SecurityConfig}, the service enforces its own {@code requireAdmin()}, and there is no admin
 * screen — it is driven by curl.
 *
 * <p>Neither endpoint calls Fintrix. {@code /preview} sends nothing at all; run it first.
 */
@RestController
@RequestMapping("/api/admin/bureau-challenge")
@RequiredArgsConstructor
public class BureauChallengeOutreachController {

    private final BureauChallengeOutreachService service;

    /**
     * Chase ONE borrower — the Customers page's failure dialog. Idempotent per borrower, like the
     * cohort run: an application already notified comes back as skipped rather than re-mailed.
     */
    @PostMapping("/notify/{applicationId}")
    public ApiResponse<OutreachSummary> notifyOne(@PathVariable Long applicationId) {
        return ApiResponse.ok(service.notifyApplication(applicationId));
    }

    /** Dry run: who would be contacted, zero sends. */
    @GetMapping("/preview")
    public ApiResponse<OutreachSummary> preview(
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(service.preview(limit));
    }

    /**
     * Sends the in-app + email nudge. Idempotent per borrower: an application already stamped
     * {@code bureauChallengeNotifiedAt} is counted and skipped, so a re-run does not re-mail. Start
     * with {@code limit=1} and check the inbox before releasing the rest.
     */
    @PostMapping("/notify")
    public ApiResponse<OutreachSummary> notifyPending(@RequestParam int limit) {
        return ApiResponse.ok(service.notifyPending(limit));
    }
}
