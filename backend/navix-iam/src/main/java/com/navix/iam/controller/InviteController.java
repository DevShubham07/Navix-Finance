package com.navix.iam.controller;

import com.navix.iam.dto.StaffDtos.AcceptInviteRequest;
import com.navix.iam.dto.StaffDtos.CreateInviteRequest;
import com.navix.iam.dto.StaffDtos.InviteResponse;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.service.InviteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff invite endpoints: Admin issues invites; invitees activate via token.
 * TODO: secure invite creation with ADMIN role.
 */
@RestController
@RequestMapping("/api/staff/invites")
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;

    @PostMapping
    public InviteResponse create(@Valid @RequestBody CreateInviteRequest request) {
        // TODO: create a new staff invite.
        return inviteService.createInvite(request);
    }

    @PostMapping("/accept")
    public StaffResponse accept(@Valid @RequestBody AcceptInviteRequest request) {
        // TODO: accept invite and activate staff account.
        return inviteService.acceptInvite(request);
    }
}
