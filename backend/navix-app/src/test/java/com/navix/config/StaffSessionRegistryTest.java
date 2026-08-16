package com.navix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link StaffSessionRegistry}: the grandfathered-null-sid path, the match/mismatch
 *  path, and the cache being honoured until {@link StaffSessionRegistry#invalidate}. */
@ExtendWith(MockitoExtension.class)
class StaffSessionRegistryTest {

    @Mock
    private StaffUserRepository staffRepository;

    private StaffUser staffWith(String activeSessionId) {
        StaffUser s = new StaffUser();
        s.setId(9L);
        s.setActiveSessionId(activeSessionId);
        return s;
    }

    @Test
    void nullSessionId_isGrandfatheredAsCurrent() {
        StaffSessionRegistry registry = new StaffSessionRegistry(staffRepository);
        assertThat(registry.isCurrent("9", null)).isTrue();
    }

    @Test
    void matchingSessionId_isCurrent() {
        when(staffRepository.findById(9L)).thenReturn(Optional.of(staffWith("sess-1")));
        StaffSessionRegistry registry = new StaffSessionRegistry(staffRepository);

        assertThat(registry.isCurrent("9", "sess-1")).isTrue();
    }

    @Test
    void staleSessionId_isNotCurrent() {
        when(staffRepository.findById(9L)).thenReturn(Optional.of(staffWith("sess-2")));
        StaffSessionRegistry registry = new StaffSessionRegistry(staffRepository);

        assertThat(registry.isCurrent("9", "sess-1")).isFalse();
    }

    @Test
    void noActiveSession_isCurrentRegardlessOfToken() {
        // A staff row that has never logged in (or cleanly logged out) has no active session —
        // nothing to compare against, so any presented sid is treated as current rather than
        // wedging the request. (In practice this token wouldn't exist without a login that set one.)
        when(staffRepository.findById(9L)).thenReturn(Optional.of(staffWith(null)));
        StaffSessionRegistry registry = new StaffSessionRegistry(staffRepository);

        assertThat(registry.isCurrent("9", "sess-1")).isTrue();
    }

    @Test
    void result_isCachedUntilInvalidated() {
        when(staffRepository.findById(9L)).thenReturn(Optional.of(staffWith("sess-1")));
        StaffSessionRegistry registry = new StaffSessionRegistry(staffRepository);

        assertThat(registry.isCurrent("9", "sess-1")).isTrue();
        assertThat(registry.isCurrent("9", "sess-1")).isTrue();
        verify(staffRepository, times(1)).findById(9L); // second call served from cache

        registry.invalidate(9L);
        assertThat(registry.isCurrent("9", "sess-1")).isTrue();
        verify(staffRepository, times(2)).findById(9L); // re-read after invalidation
    }
}
