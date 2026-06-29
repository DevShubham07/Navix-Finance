package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.common.security.ActorContext;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.AcceptInviteRequest;
import com.navix.iam.dto.StaffDtos.CreateInviteRequest;
import com.navix.iam.dto.StaffDtos.InviteResponse;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.entity.StaffInvite;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffInviteRepository;
import com.navix.iam.repository.StaffUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles Admin-issued staff invites and their one-time activation.
 *
 * <p>{@link #createInvite} mints a unique token and a 7-day expiry. {@link #acceptInvite} validates
 * the token (exists / not expired / not already accepted), then creates — or activates an existing —
 * {@link StaffUser} as {@link StaffStatus#ACTIVE} with the invited role, and stamps {@code acceptedAt}.
 *
 * <p>Email dispatch and password/credential setup are deferred (handoff §0.1); the token is returned
 * in the response for the demo flow.
 */
@Service
@RequiredArgsConstructor
public class InviteService {

    /** How long an invite token stays valid. */
    static final Duration INVITE_TTL = Duration.ofDays(7);

    private final StaffInviteRepository inviteRepository;
    private final StaffUserRepository staffUserRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** All invites (pending + accepted), most recent first, for the admin invites list. */
    @Transactional(readOnly = true)
    public List<InviteResponse> listInvites() {
        requireAdmin();
        return inviteRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(
                        b.getId() == null ? 0 : b.getId(),
                        a.getId() == null ? 0 : a.getId()))
                .map(InviteResponse::of)
                .toList();
    }

    /** Persist a new {@link StaffInvite} with a fresh unique token and a 7-day expiry. ADMIN-only. */
    @Transactional
    public InviteResponse createInvite(CreateInviteRequest request) {
        requireAdmin();
        StaffInvite invite = new StaffInvite();
        invite.setEmail(request.email());
        invite.setRole(request.role());
        invite.setToken(UUID.randomUUID().toString());
        invite.setExpiresAt(Instant.now().plus(INVITE_TTL));
        StaffInvite saved = inviteRepository.save(invite);
        // Carries the one-time accept token so STAFF_INVITED's email can include the activation link.
        eventPublisher.publishEvent(new StaffAccountEvent(null, invite.getEmail(), null,
                invite.getRole().name(), StaffAccountEvent.ChangeType.INVITED, invite.getToken(), Instant.now()));
        return InviteResponse.of(saved);
    }

    /**
     * Activate an account from a one-time invite token: validate the token, mark it accepted, and
     * create (or re-activate) the matching {@link StaffUser} with the invited role and ACTIVE status.
     *
     * @throws BusinessException if the token is invalid, expired, or already accepted
     */
    @Transactional
    public StaffResponse acceptInvite(AcceptInviteRequest request) {
        StaffInvite invite = inviteRepository.findByToken(request.token())
                .orElseThrow(() -> new BusinessException("INVALID_INVITE", "Invite token is invalid"));
        if (invite.getAcceptedAt() != null) {
            throw new BusinessException("INVITE_ALREADY_ACCEPTED", "Invite has already been accepted");
        }
        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("INVITE_EXPIRED", "Invite token has expired");
        }

        StaffUser staff = staffUserRepository.findByEmail(invite.getEmail())
                .orElseGet(StaffUser::new);
        staff.setEmail(invite.getEmail());
        staff.setName(request.name());
        staff.setRole(invite.getRole());
        staff.setStatus(StaffStatus.ACTIVE);
        StaffUser saved = staffUserRepository.save(staff);

        invite.setAcceptedAt(Instant.now());
        inviteRepository.save(invite);

        return StaffResponse.of(saved);
    }

    /** Guard: only an ADMIN may issue or list staff invites (accept stays open for the invitee). */
    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
