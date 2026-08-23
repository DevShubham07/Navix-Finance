package com.navix.iam.service;

import com.navix.common.notification.NotificationChannel;
import com.navix.common.staff.StaffPreferenceDirectory;
import com.navix.iam.repository.StaffUserRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAM-backed implementation of the {@link StaffPreferenceDirectory} port: maps a staffer's
 * {@code staff_user.email_opt_in} column to the set of channels they've opted OUT of, for the
 * notification dispatcher. Unknown/null id → opted in to everything (empty set, fail open).
 */
@Component
@RequiredArgsConstructor
public class StaffPreferenceAdapter implements StaffPreferenceDirectory {

    private final StaffUserRepository staffUserRepository;

    @Override
    @Transactional(readOnly = true)
    public Set<NotificationChannel> optedOutChannels(Long staffId) {
        if (staffId == null) {
            return Set.of();
        }
        return staffUserRepository.findById(staffId)
                .map(s -> s.isEmailOptIn() ? Set.<NotificationChannel>of() : Set.of(NotificationChannel.EMAIL))
                .orElseGet(Set::of);
    }
}
