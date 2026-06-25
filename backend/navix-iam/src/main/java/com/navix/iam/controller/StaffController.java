package com.navix.iam.controller;

import com.navix.common.web.ApiResponse;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.dto.StaffDtos.UpdateStaffRequest;
import com.navix.iam.service.StaffService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff administration endpoints. RBAC (ADMIN-only) is deferred to go-live (handoff §0.1).
 */
@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @GetMapping
    public ApiResponse<List<StaffResponse>> list() {
        return ApiResponse.ok(staffService.listStaff());
    }

    @GetMapping("/{id}")
    public ApiResponse<StaffResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(staffService.getStaff(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<StaffResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody UpdateStaffRequest request) {
        return ApiResponse.ok(staffService.updateStaff(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        staffService.disableStaff(id);
        return ApiResponse.ok(null);
    }
}
