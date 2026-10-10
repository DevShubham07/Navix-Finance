package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.BureauPhoneBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only (enforced in the service, like {@code /api/admin/bureau-backfill}); run once after the V80 deploy. */
@RestController
@RequestMapping("/api/admin/bureau-phones")
@RequiredArgsConstructor
public class BureauPhoneBackfillController {

    private final BureauPhoneBackfillService service;

    @PostMapping("/backfill")
    public ApiResponse<BureauPhoneBackfillService.Counts> backfill() {
        return ApiResponse.ok(service.backfill());
    }
}
