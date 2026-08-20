package com.navix.app.provider;

import com.navix.common.web.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/provider-apis")
@RequiredArgsConstructor
public class ProviderApiWorkbenchController {
    private final ProviderApiWorkbenchService service;

    /**
     * A manual run needs to outlast the API it is exercising: the bureau read timeout is 90s, so a 5s
     * cap here would have made the slowest — and most interesting — call untestable from the dashboard.
     */
    private static final Duration BUREAU_TIMEOUT = Duration.ofSeconds(95);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(35);

    public record ExecuteRequest(String operation, String provider, Map<String,Object> input) {}

    @GetMapping("/catalog") public ApiResponse<List<ProviderApiWorkbenchService.CatalogItem>> catalog() { return ApiResponse.ok(service.catalog()); }

    /** Filtered, paged history. Every filter is optional. */
    @GetMapping("/history")
    public ApiResponse<ProviderApiWorkbenchService.HistoryPage> history(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.history(
                new ProviderApiWorkbenchService.HistoryQuery(provider, operation, status, source, applicationId, from, to),
                page, size));
    }

    /** One call WITH its raw request/response — kept off the list so a page stays small. */
    @GetMapping("/history/{id}")
    public ApiResponse<ProviderApiWorkbenchService.ExecutionView> detail(@PathVariable Long id) { return ApiResponse.ok(service.detail(id)); }

    @PostMapping("/execute")
    public ApiResponse<ProviderApiWorkbenchService.ExecutionView> execute(@RequestBody ExecuteRequest request) {
        Duration timeout = "BUREAU".equalsIgnoreCase(request.operation()) ? BUREAU_TIMEOUT : DEFAULT_TIMEOUT;
        return ApiResponse.ok(service.execute(request.operation(), request.provider(), request.input(), null, timeout));
    }
}
