package com.navix.iam.service;

import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * IAM-backed implementation of the {@link StaffDirectory} port: answers staff
 * lookups for other modules against the real {@code staff_user} table.
 */
@Component
@RequiredArgsConstructor
public class StaffDirectoryAdapter implements StaffDirectory {

    private final StaffUserRepository staffUserRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveWithRole(Long staffId, String role) {
        if (staffId == null || role == null) {
            return false;
        }
        return staffUserRepository.findById(staffId)
                .map(s -> s.getStatus() == StaffStatus.ACTIVE && s.getRole().name().equals(role))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StaffSummary> findStaff(Long staffId) {
        if (staffId == null) {
            return Optional.empty();
        }
        return staffUserRepository.findById(staffId).map(StaffDirectoryAdapter::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffSummary> listActive(String role) {
        StaffRole parsed = parseRole(role);
        if (parsed == null) {
            return List.of();
        }
        return staffUserRepository.findByRoleAndStatusOrderByIdAsc(parsed, StaffStatus.ACTIVE)
                .stream().map(StaffDirectoryAdapter::toSummary).toList();
    }

    /** Lenient role parse — an unknown name yields {@code null} (→ empty result). */
    private static StaffRole parseRole(String role) {
        if (role == null) {
            return null;
        }
        try {
            return StaffRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static StaffSummary toSummary(StaffUser s) {
        return new StaffSummary(s.getId(), s.getName(), s.getRole().name(),
                s.getStatus() == StaffStatus.ACTIVE);
    }
}
