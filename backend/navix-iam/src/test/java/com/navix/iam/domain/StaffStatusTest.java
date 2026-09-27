package com.navix.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link StaffStatus#canTransitionTo}. */
class StaffStatusTest {

    @Test
    void sameStatusIsAlwaysIdempotent() {
        for (StaffStatus s : StaffStatus.values()) {
            assertThat(s.canTransitionTo(s)).as("%s -> %s", s, s).isTrue();
        }
    }

    @Test
    void activeAndDisabledTransitionEitherWay() {
        assertThat(StaffStatus.ACTIVE.canTransitionTo(StaffStatus.DISABLED)).isTrue();
        assertThat(StaffStatus.DISABLED.canTransitionTo(StaffStatus.ACTIVE)).isTrue();
    }

    @Test
    void invitedMayMoveToActiveOrDisabled() {
        assertThat(StaffStatus.INVITED.canTransitionTo(StaffStatus.ACTIVE)).isTrue();
        assertThat(StaffStatus.INVITED.canTransitionTo(StaffStatus.DISABLED)).isTrue();
    }

    @Test
    void nothingMovesBackToInvited() {
        assertThat(StaffStatus.ACTIVE.canTransitionTo(StaffStatus.INVITED)).isFalse();
        assertThat(StaffStatus.DISABLED.canTransitionTo(StaffStatus.INVITED)).isFalse();
    }
}
