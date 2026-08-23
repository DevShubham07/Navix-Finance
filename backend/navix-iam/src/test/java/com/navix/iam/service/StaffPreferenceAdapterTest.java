package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.navix.common.notification.NotificationChannel;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaffPreferenceAdapterTest {

    @Mock
    private StaffUserRepository staffUserRepository;

    private StaffPreferenceAdapter adapter;

    private static StaffUser staff(Long id, boolean emailOptIn) {
        StaffUser u = new StaffUser();
        u.setId(id);
        u.setEmail("jane@navix.test");
        u.setName("Jane");
        u.setRole(StaffRole.ADMIN);
        u.setStatus(StaffStatus.ACTIVE);
        u.setEmailOptIn(emailOptIn);
        return u;
    }

    @Test
    void optedInStaffHasNoSuppressedChannels() {
        adapter = new StaffPreferenceAdapter(staffUserRepository);
        when(staffUserRepository.findById(1L)).thenReturn(Optional.of(staff(1L, true)));

        assertThat(adapter.optedOutChannels(1L)).isEmpty();
    }

    @Test
    void optedOutStaffSuppressesEmail() {
        adapter = new StaffPreferenceAdapter(staffUserRepository);
        when(staffUserRepository.findById(1L)).thenReturn(Optional.of(staff(1L, false)));

        assertThat(adapter.optedOutChannels(1L)).isEqualTo(Set.of(NotificationChannel.EMAIL));
    }

    @Test
    void unknownStaffIdFailsOpenToEmptySet() {
        adapter = new StaffPreferenceAdapter(staffUserRepository);
        when(staffUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(adapter.optedOutChannels(99L)).isEmpty();
        assertThat(adapter.optedOutChannels(null)).isEmpty();
    }
}
