package com.navix.iam.controller;

import com.navix.common.web.ApiResponse;
import com.navix.iam.dto.StaffDtos.AcceptInviteRequest;
import com.navix.iam.dto.StaffDtos.CreateInviteRequest;
import com.navix.iam.dto.StaffDtos.InviteResponse;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.service.InviteService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff invite endpoints: Admin issues invites; invitees activate via a one-time token.
 * RBAC (ADMIN-only on create) is deferred to go-live (handoff §0.1).
 */
@RestController
@RequestMapping("/api/staff/invites")
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;

    /** List all invites (pending + accepted) for the admin invites screen. */
    @GetMapping
    public ApiResponse<List<InviteResponse>> list() {
        return ApiResponse.ok(inviteService.listInvites());
    }

    @PostMapping
    public ApiResponse<InviteResponse> create(@Valid @RequestBody CreateInviteRequest request) {
        return ApiResponse.ok(inviteService.createInvite(request));
    }

    @PostMapping("/accept")
    public ApiResponse<StaffResponse> accept(@Valid @RequestBody AcceptInviteRequest request) {
        return ApiResponse.ok(inviteService.acceptInvite(request));
    }
}
