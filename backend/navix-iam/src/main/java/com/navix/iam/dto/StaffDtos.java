package com.navix.iam.dto;

import com.navix.iam.domain.BlocklistType;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.entity.StaffInvite;
import com.navix.iam.entity.StaffUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request/response payloads for the IAM module. Views are mapped from entities via the static
 * {@code of(...)} factories (mirrors the loan module convention).
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

    /**
     * Returned after an invite is created. The one-time {@code token} is surfaced here because the
     * demo flow has no email dispatch yet (real auth / email is deferred — handoff §0.1); at go-live
     * the token is delivered by email and omitted from the response.
     */
    public record InviteResponse(
            Long id,
            String email,
            StaffRole role,
            String token,
            Instant expiresAt
    ) {

        public static InviteResponse of(StaffInvite invite) {
            return new InviteResponse(invite.getId(), invite.getEmail(), invite.getRole(),
                    invite.getToken(), invite.getExpiresAt());
        }
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

        public static StaffResponse of(StaffUser user) {
            return new StaffResponse(user.getId(), user.getEmail(), user.getName(),
                    user.getRole(), user.getStatus());
        }
    }

    /** Admin adds an identifier (PAN, Aadhaar ref, phone, device, bank account) to the blocklist. */
    public record AddBlocklistRequest(
            @NotNull BlocklistType type,
            @NotBlank String value,
            String reason
    ) {
    }

    /** Standard blocklist-entry representation returned to clients. */
    public record BlocklistResponse(
            Long id,
            BlocklistType type,
            String value,
            String reason,
            boolean active
    ) {

        public static BlocklistResponse of(BlocklistEntry entry) {
            return new BlocklistResponse(entry.getId(), entry.getType(), entry.getValue(),
                    entry.getReason(), entry.isActive());
        }
    }
}
