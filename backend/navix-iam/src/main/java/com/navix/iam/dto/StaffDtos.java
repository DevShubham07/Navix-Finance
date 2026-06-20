package com.navix.iam.dto;

import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request/response payloads for the IAM module.
 * TODO: extend as the staff admin and invite flows are fleshed out.
 */
public final class StaffDtos {

    private StaffDtos() {
    }

    /** Admin invites a new staff member by email + role. */
    public record CreateInviteRequest(
            @Email @NotBlank String email,
            @NotNull StaffRole role
    ) {
    }

    /** Returned after an invite is created. */
    public record InviteResponse(
            Long id,
            String email,
            StaffRole role,
            Instant expiresAt
    ) {
    }

    /** Invitee activates their account using the one-time token. */
    public record AcceptInviteRequest(
            @NotBlank String token,
            @NotBlank String name
    ) {
    }

    /** Update an existing staff member's role and/or status. */
    public record UpdateStaffRequest(
            @NotNull StaffRole role,
            @NotNull StaffStatus status
    ) {
    }

    /** Standard staff representation returned to clients. */
    public record StaffResponse(
            Long id,
            String email,
            String name,
            StaffRole role,
            StaffStatus status
    ) {
    }
}
