package com.navix.iam.controller;

import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.dto.StaffDtos.UpdateStaffRequest;
import com.navix.iam.service.StaffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff administration endpoints.
 * TODO: secure with ADMIN role and wire real responses.
 */
@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @GetMapping
    public List<StaffResponse> list() {
        // TODO: return staff list.
        return staffService.listStaff();
    }

    @GetMapping("/{id}")
    public StaffResponse get(@PathVariable Long id) {
        // TODO: return single staff member.
        return staffService.getStaff(id);
    }

    @PutMapping("/{id}")
    public StaffResponse update(@PathVariable Long id, @Valid @RequestBody UpdateStaffRequest request) {
        // TODO: update staff role/status.
        return staffService.updateStaff(id, request);
    }

    @DeleteMapping("/{id}")
    public void disable(@PathVariable Long id) {
        // TODO: disable staff member.
        staffService.disableStaff(id);
    }
}
