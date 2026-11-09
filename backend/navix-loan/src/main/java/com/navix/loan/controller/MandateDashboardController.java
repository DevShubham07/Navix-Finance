package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.MandateDashboardService;
import com.navix.loan.service.MandateDashboardService.Actor;
import com.navix.loan.service.MandateDashboardService.DebitPage;
import com.navix.loan.service.MandateDashboardService.DebitRow;
import com.navix.loan.service.MandateDashboardService.MandatePage;
import com.navix.loan.service.MandateDashboardService.Summary;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "Mandates &amp; debits" console. Read-only; COLLECTION_HEAD and ADMIN, enforced in the service. Actions
 * on a mandate or debit go through the existing per-application and collections endpoints.
 */
@RestController
@RequestMapping("/api/mandates")
@RequiredArgsConstructor
public class MandateDashboardController {

    private final MandateDashboardService dashboard;

    /** Tiles and the 30-day amount-debited series. */
    @GetMapping("/summary")
    public ApiResponse<Summary> summary() {
        return ApiResponse.ok(dashboard.summary());
    }

    @GetMapping
    public ApiResponse<MandatePage> mandates(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(dashboard.mandates(q, status, page, size));
    }

    /** Every debit presented against one mandate. */
    @GetMapping("/{mandateId}/debits")
    public ApiResponse<List<DebitRow>> mandateDebits(@PathVariable Long mandateId) {
        return ApiResponse.ok(dashboard.debitsForMandate(mandateId));
    }

    @GetMapping("/debits")
    public ApiResponse<DebitPage> debits(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String trigger,
                                         @RequestParam(required = false) Long actorId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(dashboard.debits(q, status, trigger, actorId, from, to, page, size));
    }

    /** Staff who have presented a debit — the "presented by" filter. */
    @GetMapping("/debits/actors")
    public ApiResponse<List<Actor>> actors() {
        return ApiResponse.ok(dashboard.actors());
    }
}
