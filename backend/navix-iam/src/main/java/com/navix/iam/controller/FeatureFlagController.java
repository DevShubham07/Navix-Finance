package com.navix.iam.controller;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.web.ApiResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the dev-controlled feature flags, so the frontend can hide UI for a disabled
 * feature (e.g. the staff "Referral payouts" nav/page).
 *
 * <p><b>GET only</b> — there is intentionally no PUT/POST. Flags are changed solely via direct SQL
 * against the {@code feature_flag} table; nothing in the app (ADMIN included) can write them. This
 * endpoint exposes only flag <i>state</i>, never a way to change it. Authenticated like every other
 * {@code /api/**} route (both staff and borrower sessions carry a JWT).
 */
@RestController
@RequestMapping("/api/feature-flags")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    /** All feature flags as {@code key → enabled}. */
    @GetMapping
    public ApiResponse<Map<String, Boolean>> get() {
        return ApiResponse.ok(featureFlagService.all());
    }
}
