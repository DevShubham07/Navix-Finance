package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.CreateStaffRequest;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.dto.StaffDtos.UpdateStaffRequest;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
        return StaffResponse.of(staffUserRepository.save(staff));
    }

    @Transactional(readOnly = true)
    public List<StaffResponse> listStaff() {
        return staffUserRepository.findAll().stream()
                .map(StaffResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffResponse getStaff(Long id) {
        return StaffResponse.of(requireStaff(id));
    }

    /** Update an existing staff member's role and status. */
    @Transactional
    public StaffResponse updateStaff(Long id, UpdateStaffRequest request) {
        StaffUser staff = requireStaff(id);
        staff.setRole(request.role());
        staff.setStatus(request.status());
        return StaffResponse.of(staffUserRepository.save(staff));
    }

    /** Deactivate a staff member (status → {@link StaffStatus#DISABLED}); idempotent. */
    @Transactional
    public void disableStaff(Long id) {
        StaffUser staff = requireStaff(id);
        staff.setStatus(StaffStatus.DISABLED);
        staffUserRepository.save(staff);
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
