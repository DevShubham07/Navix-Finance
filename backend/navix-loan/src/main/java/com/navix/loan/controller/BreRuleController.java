package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.bre.BlockedPincodeService;
import com.navix.loan.bre.BlockedPincodeService.PincodeList;
import com.navix.loan.bre.BreRuleService;
import com.navix.loan.bre.BreRuleService.BreRuleView;
import com.navix.loan.bre.BreRuleService.BreThresholdView;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BRE rules tab. Under {@code /api/staff/**} (staff audience); DSA refusal and ADMIN-only writes live in the service. */
@RestController
@RequiredArgsConstructor
public class BreRuleController {

    private final BreRuleService service;
    private final BlockedPincodeService pincodes;

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

    // ---- blocked pin code list (ADMIN; enforced in the service) ----

    @GetMapping("/api/staff/bre-rules/pincodes")
    public ApiResponse<PincodeList> searchPincodes(@RequestParam(required = false) String q) {
        return ApiResponse.ok(pincodes.search(q));
    }

    @PostMapping("/api/staff/bre-rules/pincodes")
    public ApiResponse<PincodeList> addPincodes(@RequestBody Map<String, List<String>> body) {
        return ApiResponse.ok(pincodes.add(body == null ? null : body.get("pincodes")));
    }

    @PutMapping("/api/staff/bre-rules/pincodes")
    public ApiResponse<PincodeList> replacePincodes(@RequestBody Map<String, List<String>> body) {
        return ApiResponse.ok(pincodes.replace(body == null ? null : body.get("pincodes")));
    }

    @DeleteMapping("/api/staff/bre-rules/pincodes/{pincode}")
    public ApiResponse<PincodeList> removePincode(@PathVariable String pincode) {
        return ApiResponse.ok(pincodes.remove(pincode));
    }
}
