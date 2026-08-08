package com.navix.app.provider;

import com.navix.common.web.ApiResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/provider-apis")
@RequiredArgsConstructor
public class ProviderApiWorkbenchController {
    private final ProviderApiWorkbenchService service;
    public record ExecuteRequest(String operation, String provider, Map<String,Object> input) {}
    @GetMapping("/catalog") public ApiResponse<List<ProviderApiWorkbenchService.CatalogItem>> catalog() { return ApiResponse.ok(service.catalog()); }
    @GetMapping("/history") public ApiResponse<List<ProviderApiWorkbenchService.ExecutionView>> history() { return ApiResponse.ok(service.history()); }
    @PostMapping("/execute") public ApiResponse<ProviderApiWorkbenchService.ExecutionView> execute(@RequestBody ExecuteRequest request) { return ApiResponse.ok(service.execute(request.operation(), request.provider(), request.input(), null, Duration.ofSeconds(5))); }
}
