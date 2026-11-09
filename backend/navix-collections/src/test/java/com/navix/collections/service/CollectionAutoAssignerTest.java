package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.staff.StaffDirectory;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollectionAutoAssignerTest {

    private final CollectionCaseRepository cases = mock(CollectionCaseRepository.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);

    private static ApplicationTransitionedEvent to(String status, Long loanId) {
        return new ApplicationTransitionedEvent(1L, 2L, loanId, "DISBURSED", status, "ACTIVATE",
                null, "7", "DISBURSEMENT_HEAD", Instant.now(), null);
    }

    @Test
    void activatedLoanGetsACaseOnTheConfiguredOfficer() {
        when(cases.findFirstByLoanIdOrderByCreatedAtDesc(219L)).thenReturn(Optional.empty());
        when(staff.isActiveWithRole(29L, "COLLECTION_EXECUTIVE")).thenReturn(true);

        new CollectionAutoAssigner(cases, staff, 29L).onTransition(to("ACTIVE", 219L));

        ArgumentCaptor<CollectionCase> saved = ArgumentCaptor.forClass(CollectionCase.class);
        verify(cases).save(saved.capture());
        assertThat(saved.getValue().getLoanId()).isEqualTo(219L);
        assertThat(saved.getValue().getAssignedOfficerId()).isEqualTo(29L);
    }

    @Test
    void skipsWhenUnconfiguredNotActivationExistingCaseOrInactiveOfficer() {
        new CollectionAutoAssigner(cases, staff, null).onTransition(to("ACTIVE", 219L));
        new CollectionAutoAssigner(cases, staff, 29L).onTransition(to("DISBURSED", 219L));

        when(cases.findFirstByLoanIdOrderByCreatedAtDesc(220L)).thenReturn(Optional.of(new CollectionCase()));
        new CollectionAutoAssigner(cases, staff, 29L).onTransition(to("ACTIVE", 220L));

        when(cases.findFirstByLoanIdOrderByCreatedAtDesc(221L)).thenReturn(Optional.empty());
        when(staff.isActiveWithRole(29L, "COLLECTION_EXECUTIVE")).thenReturn(false);
        new CollectionAutoAssigner(cases, staff, 29L).onTransition(to("ACTIVE", 221L));

        verify(cases, never()).save(any());
    }
}
