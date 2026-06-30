package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.BorrowerPreferencesService;
import com.navix.loan.service.BorrowerPreferencesService.PreferencesView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrower-scoped notification preferences (Phase 2.2). The caller is resolved from the JWT subject;
 * a borrower reads/writes only their own row. Replaces the old browser-only "demo settings".
 */
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class BorrowerPreferencesController {

    private final BorrowerPreferencesService preferencesService;

    /** The calling borrower's saved preferences (all-on defaults when none saved). */
    @GetMapping
    public ApiResponse<PreferencesView> get() {
        return ApiResponse.ok(preferencesService.getMine());
    }

    /** Upsert the calling borrower's preferences. */
    @PutMapping
    public ApiResponse<PreferencesView> update(@RequestBody PreferencesView req) {
        return ApiResponse.ok(preferencesService.updateMine(req));
    }
}
