package com.navix.common.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link FeatureFlagService}: present row wins, absent row falls back to the supplied
 * default (and to {@code false} for the no-default overload), and {@link FeatureFlagService#all()}
 * projects every row to {@code key → enabled}.
 */
@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock
    private FeatureFlagRepository repository;

    private FeatureFlagService service() {
        return new FeatureFlagService(repository);
    }

    private static FeatureFlag flag(String key, boolean enabled) {
        FeatureFlag f = new FeatureFlag();
        f.setFlagKey(key);
        f.setEnabled(enabled);
        return f;
    }

    @Test
    void isEnabled_returnsStoredValue_whenRowPresent() {
        when(repository.findByFlagKey("referral")).thenReturn(Optional.of(flag("referral", true)));

        assertThat(service().isEnabled("referral", false)).isTrue();
    }

    @Test
    void isEnabled_storedFalse_overridesDefaultTrue() {
        when(repository.findByFlagKey("referral")).thenReturn(Optional.of(flag("referral", false)));

        // The DB row wins even when the caller's fallback default is true.
        assertThat(service().isEnabled("referral", true)).isFalse();
    }

    @Test
    void isEnabled_returnsDefault_whenRowMissing() {
        when(repository.findByFlagKey("referral")).thenReturn(Optional.empty());

        assertThat(service().isEnabled("referral", true)).isTrue();
        assertThat(service().isEnabled("referral", false)).isFalse();
    }

    @Test
    void isEnabled_noDefaultOverload_defaultsToFalse_whenRowMissing() {
        when(repository.findByFlagKey("ghost")).thenReturn(Optional.empty());

        assertThat(service().isEnabled("ghost")).isFalse();
    }

    @Test
    void all_projectsEveryRowToKeyEnabled() {
        when(repository.findAll()).thenReturn(List.of(flag("referral", true), flag("beta", false)));

        assertThat(service().all())
                .containsEntry("referral", true)
                .containsEntry("beta", false)
                .hasSize(2);
    }
}
