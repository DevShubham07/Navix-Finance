package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.bre.BreRuleService;
import com.navix.loan.bre.BreRuleService.BreRuleView;
import com.navix.loan.bre.BreRuleService.BreThresholdView;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** BRE rules tab. Under {@code /api/staff/**} (staff audience); DSA refusal and ADMIN-only writes live in the service. */
@RestController
@RequiredArgsConstructor
public class BreRuleController {

    private final BreRuleService service;

    @GetMapping("/api/staff/bre-rules")
    public ApiResponse<List<BreRuleView>> list() {
        return ApiResponse.ok(service.list());
    }

    @PutMapping("/api/staff/bre-rules/{ruleKey}/enabled")
    public ApiResponse<BreRuleView> setEnabled(@PathVariable String ruleKey,
                                               @RequestBody Map<String, Boolean> body) {
        // A missing/garbled body must not silently switch a fraud rule off.
        Boolean enabled = body == null ? null : body.get("enabled");
        if (enabled == null) {
            throw new com.navix.common.exception.BusinessException("ENABLED_REQUIRED", "Send {\"enabled\": true|false}");
        }
        return ApiResponse.ok(service.setEnabled(ruleKey, enabled));
    }

    @PutMapping("/api/staff/bre-rules/thresholds/{key}")
    public ApiResponse<BreThresholdView> setThreshold(@PathVariable String key,
                                                      @RequestBody Map<String, Number> body) {
        return ApiResponse.ok(service.setThreshold(key, body.get("value")));
    }
}
