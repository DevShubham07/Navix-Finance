package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.common.security.ActorContext;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.CreateStaffRequest;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.dto.StaffDtos.UpdateMyProfileRequest;
import com.navix.iam.dto.StaffDtos.UpdateStaffRequest;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages staff user accounts: listing, single fetch, role/status changes and deactivation.
 * Backed by {@link StaffUserRepository}; all responses are mapped to {@link StaffResponse} views.
 *
 * <p>Authentication is deferred (handoff §0.1) — this service owns the account lifecycle only.
 */
@Service
@RequiredArgsConstructor
public class StaffService {

    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Admin-only: create a staff account directly with an email + password so the
     * new staffer can sign in immediately (unlike the invite flow, which leaves the
     * password unset). Enforces ADMIN, rejects a duplicate email, BCrypt-hashes the
     * password, and activates the account.
     */
    @Transactional
    public StaffResponse createStaff(CreateStaffRequest request) {
        requireAdmin();
        String email = request.email().trim();
        if (staffUserRepository.findByEmail(email).isPresent()) {
            throw new BusinessException("EMAIL_TAKEN", "A staff account with this email already exists");
        }
        StaffUser staff = new StaffUser();
        staff.setEmail(email);
        staff.setName(request.name().trim());
        staff.setRole(request.role());
        staff.setStatus(StaffStatus.ACTIVE);
        staff.setPasswordHash(passwordEncoder.encode(request.password()));
        StaffUser saved = staffUserRepository.save(staff);
        eventPublisher.publishEvent(new StaffAccountEvent(saved.getId(), saved.getEmail(), saved.getName(),
                saved.getRole().name(), StaffAccountEvent.ChangeType.CREATED, null, Instant.now()));
        return StaffResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<StaffResponse> listStaff() {
        requireAdmin();
        return staffUserRepository.findAll().stream()
                .map(StaffResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffResponse getStaff(Long id) {
        requireAdmin();
        return StaffResponse.of(requireStaff(id));
    }

    /** Update an existing staff member's role and status. */
    @Transactional
    public StaffResponse updateStaff(Long id, UpdateStaffRequest request) {
        requireAdmin();
        StaffUser staff = requireStaff(id);
        StaffRole previousRole = staff.getRole();
        staff.setRole(request.role());
        staff.setStatus(request.status());
        StaffUser saved = staffUserRepository.save(staff);
        if (previousRole != request.role()) {
            eventPublisher.publishEvent(new StaffAccountEvent(saved.getId(), saved.getEmail(), saved.getName(),
                    saved.getRole().name(), StaffAccountEvent.ChangeType.ROLE_CHANGED, null, Instant.now()));
        }
        return StaffResponse.of(saved);
    }

    /** Deactivate a staff member (status → {@link StaffStatus#DISABLED}); idempotent. */
    @Transactional
    public void disableStaff(Long id) {
        requireAdmin();
        StaffUser staff = requireStaff(id);
        staff.setStatus(StaffStatus.DISABLED);
        staffUserRepository.save(staff);
        eventPublisher.publishEvent(new StaffAccountEvent(staff.getId(), staff.getEmail(), staff.getName(),
                staff.getRole().name(), StaffAccountEvent.ChangeType.DISABLED, null, Instant.now()));
    }

    /** The calling staffer's own account (resolved from the JWT subject). Any authenticated staff. */
    @Transactional(readOnly = true)
    public StaffResponse getMyProfile() {
        return StaffResponse.of(requireStaff(currentStaffId()));
    }

    /**
     * The calling staffer self-edits their own display name + org fields (department/designation).
     * Role and status are NOT editable here (admin-only via {@link #updateStaff}).
     */
    @Transactional
    public StaffResponse updateMyProfile(UpdateMyProfileRequest req) {
        StaffUser staff = requireStaff(currentStaffId());
        String name = trimToNull(req.name());
        if (name != null) {
            staff.setName(name);
        }
        staff.setDepartment(trimToNull(req.department()));
        staff.setDesignation(trimToNull(req.designation()));
        return StaffResponse.of(staffUserRepository.save(staff));
    }

    private static Long currentStaffId() {
        var actor = ActorContext.get();
        String role = actor.role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role) || "SYSTEM".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff authentication required");
        }
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN", "Could not resolve staff identity");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private StaffUser requireStaff(Long id) {
        return staffUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StaffUser", String.valueOf(id)));
    }

    /** Guard: only an ADMIN may manage staff accounts. */
    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
