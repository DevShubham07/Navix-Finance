package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeparationOfDutiesGuardTest {

    private SeparationOfDutiesGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SeparationOfDutiesGuard();
    }

    @Test
    void enforcePassesWhenActorIsDistinctFromPriors() {
        assertThatCode(() -> guard.enforce(3L, List.of(1L, 2L))).doesNotThrowAnyException();
    }

    @Test
    void enforceRejectsRepeatedActor() {
        assertThatThrownBy(() -> guard.enforce(2L, List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(SeparationOfDutiesGuard.VIOLATION_CODE);
    }

    @Test
    void enforceVarargsRejectsRepeatedActor() {
        assertThatThrownBy(() -> guard.enforce(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(SeparationOfDutiesGuard.VIOLATION_CODE);
    }

    @Test
    void enforceIgnoresNullPriorIds() {
        assertThatCode(() -> guard.enforce(5L, Arrays.asList(null, 1L, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void enforceWithNoPriorsPasses() {
        assertThatCode(() -> guard.enforce(5L, List.of())).doesNotThrowAnyException();
        assertThatCode(() -> guard.enforce(5L, (java.util.Collection<Long>) null))
                .doesNotThrowAnyException();
    }

    @Test
    void enforceRejectsNullActor() {
        assertThatThrownBy(() -> guard.enforce(null, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(SeparationOfDutiesGuard.VIOLATION_CODE);
    }

    @Test
    void assertDistinctActorsPassesForThreeDifferentActors() {
        assertThatCode(() -> guard.assertDistinctActors(1L, 2L, 3L)).doesNotThrowAnyException();
    }

    @Test
    void assertDistinctActorsRejectsApproverEqualToReviewer() {
        assertThatThrownBy(() -> guard.assertDistinctActors(1L, 1L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(SeparationOfDutiesGuard.VIOLATION_CODE);
    }

    @Test
    void assertDistinctActorsRejectsReleaserEqualToApprover() {
        assertThatThrownBy(() -> guard.assertDistinctActors(1L, 2L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(SeparationOfDutiesGuard.VIOLATION_CODE);
    }
}
