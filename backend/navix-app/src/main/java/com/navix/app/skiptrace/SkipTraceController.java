package com.navix.app.skiptrace;

import com.navix.app.skiptrace.SkipTraceService.SkipTraceView;
import com.navix.common.web.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Skip Tracer tab's two calls. Mounted under {@code /api/staff/**}, which {@code SecurityConfig}
 * already gates on the staff audience; who may <i>run</i> (COLLECTION_HEAD / ADMIN) versus merely
 * <i>read</i> (any staff but DSA) is decided in {@link SkipTraceService}, beside the rule it enforces.
 */
@RestController
@RequestMapping("/api/staff/skip-trace")
@RequiredArgsConstructor
public class SkipTraceController {

    private final SkipTraceService service;

    /** Every run for this customer, newest first — free to read, no provider call. */
    @GetMapping("/customers/{customerId}")
    public ApiResponse<List<SkipTraceView>> history(@PathVariable Long customerId) {
        return ApiResponse.ok(service.history(customerId));
    }

    /** One billable Digitap lookup, recorded whatever the outcome. */
    @PostMapping("/customers/{customerId}")
    public ApiResponse<SkipTraceView> run(@PathVariable Long customerId) {
        return ApiResponse.ok(service.run(customerId));
    }
}
