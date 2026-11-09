package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.IntakeSwitchService;
import com.navix.loan.service.IntakeSwitchService.ClosedStatus;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Close-applications switch. Under {@code /api/staff/**} (staff audience); ADMIN enforced in the service. */
@RestController
@RequiredArgsConstructor
public class IntakeSwitchController {

    private final IntakeSwitchService service;

    @GetMapping("/api/staff/applications-closed")
    public ApiResponse<ClosedStatus> status() {
        return ApiResponse.ok(service.status());
    }

    @PutMapping("/api/staff/applications-closed")
    public ApiResponse<ClosedStatus> setClosed(@RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(service.setClosed(Boolean.TRUE.equals(body.get("closed"))));
    }
}
