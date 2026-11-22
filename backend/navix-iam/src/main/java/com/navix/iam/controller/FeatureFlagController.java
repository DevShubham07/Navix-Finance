package com.navix.iam.controller;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.web.ApiResponse;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the dev-controlled feature flags, so the frontend can hide UI for a disabled
 * feature (e.g. the staff "Referral payouts" nav/page).
 *
 * <p><b>GET only</b> — this endpoint exposes flag <i>state</i>, never a way to change it. Flags are
 * changed by SQL, plus the ADMIN-only Settings toggles and BRE rules tab (separate endpoints).
 * Authenticated like every other {@code /api/**} route (both staff and borrower sessions carry a JWT).
 * A borrower token does not see the BRE fraud-rule state: {@code bre-*} keys and
 * {@code bureau-auto-reject} are dropped, except {@code bre-self-employed-reject} (the employment page
 * needs it and it is product eligibility, not fraud-sensitive).
 */
@RestController
@RequestMapping("/api/feature-flags")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    /** All feature flags as {@code key → enabled}. */
    @GetMapping
    public ApiResponse<Map<String, Boolean>> get() {
        Map<String, Boolean> flags = featureFlagService.all();
        CurrentActor actor = ActorContext.get();
        if (actor != null && "BORROWER".equals(actor.role())) {
            flags = new LinkedHashMap<>(flags);
            flags.keySet().removeIf(k -> (k.startsWith("bre-") && !k.equals("bre-self-employed-reject"))
                    || k.equals("bureau-auto-reject"));
        }
        return ApiResponse.ok(flags);
    }
}
