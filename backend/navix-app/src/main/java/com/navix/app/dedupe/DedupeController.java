package com.navix.app.dedupe;

import com.navix.app.dedupe.DedupeDtos.DedupeView;
import com.navix.app.dedupe.DedupeDtos.MobileMatchView;
import com.navix.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Dedupe tab's single read. Authz (staff, not DSA) lives in {@link DedupeService}. */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class DedupeController {

    private final DedupeService service;
    private final MobileMatchService mobileMatches;

    @GetMapping("/{customerId}/dedupe")
    public ApiResponse<DedupeView> dedupe(@PathVariable Long customerId) {
        return ApiResponse.ok(service.dedupe(customerId));
    }

    @GetMapping("/{customerId}/mobile-matches")
    public ApiResponse<MobileMatchView> mobileMatches(@PathVariable Long customerId) {
        return ApiResponse.ok(mobileMatches.matches(customerId));
    }
}
