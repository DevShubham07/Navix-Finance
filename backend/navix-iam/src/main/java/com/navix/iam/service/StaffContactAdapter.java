package com.navix.iam.service;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.RecipientType;
import com.navix.common.staff.StaffContactDirectory;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAM-backed implementation of the PII-bearing {@link StaffContactDirectory} port for the notification
 * engine — resolves a staff id (or all ACTIVE holders of a role) into {@link ContactInfo} carrying the
 * email. Separate from {@link StaffDirectoryAdapter}/{@code StaffSummary} (which deliberately omits PII).
 * Staff have no mobile column, so {@code mobile} is always null → staff never receive SMS.
 */
@Component
@RequiredArgsConstructor
public class StaffContactAdapter implements StaffContactDirectory {

    private final StaffUserRepository staffUserRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ContactInfo> contact(Long staffId) {
        if (staffId == null) {
            return Optional.empty();
        }
        return staffUserRepository.findById(staffId).map(StaffContactAdapter::toContact);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContactInfo> contactsByRole(String role) {
        StaffRole parsed = parseRole(role);
        if (parsed == null) {
            return List.of();
        }
        return staffUserRepository.findByRoleAndStatusOrderByIdAsc(parsed, StaffStatus.ACTIVE)
                .stream().map(StaffContactAdapter::toContact).toList();
    }

    private static ContactInfo toContact(StaffUser s) {
        return new ContactInfo(RecipientType.STAFF, s.getId(), s.getName(), s.getEmail(), null, s.getRole().name());
    }

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
}
