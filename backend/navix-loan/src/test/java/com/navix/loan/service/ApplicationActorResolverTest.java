package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.navix.common.loan.ApplicationActorDirectory.HandledBy;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * "Who handled this file" — read from the event trail, never stored.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationActorResolverTest {

    @Mock
    private ApplicationEventRepository eventRepository;
    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private StaffDirectory staffDirectory;

    private ApplicationActorResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ApplicationActorResolver(eventRepository, applicationRepository, staffDirectory);
    }

    /** The repository returns newest-first, so the first hit per application is the live decision. */
    @Test
    void keepsTheMostRecentDecisionPerApplication() {
        stubEvents("SANCTION", List.of(
                event(1L, "SANCTION", "5"),   // newest — the decision that stands
                event(1L, "REJECT_LEAD", "6")));
        stubEvents("VALIDATE_SUCCESS", List.of());
        when(staffDirectory.findStaff(5L)).thenReturn(Optional.of(staff(5L, "Priya Nair")));

        Map<Long, HandledBy> result = resolver.byApplicationId(List.of(1L));

        assertThat(result.get(1L).creditDecidedByName()).isEqualTo("Priya Nair");
        assertThat(result.get(1L).disbursedByName()).isNull();
    }

    /**
     * The Disbursement Head's accept emits VALIDATE_SUCCESS today; DISB_ACCEPT is the retired label
     * still sitting on historical rows. Both must name the disburser.
     */
    @Test
    void resolvesTheDisburserFromEitherAcceptLabel() {
        stubEvents("SANCTION", List.of());
        stubEvents("VALIDATE_SUCCESS", List.of(event(2L, "DISB_ACCEPT", "8")));
        when(staffDirectory.findStaff(8L)).thenReturn(Optional.of(staff(8L, "Rahul Mehta")));

        Map<Long, HandledBy> result = resolver.byApplicationId(List.of(2L));

        assertThat(result.get(2L).disbursedByName()).isEqualTo("Rahul Mehta");
        assertThat(result.get(2L).disbursedById()).isEqualTo(8L);
    }

    /** A deactivated staffer, or a non-numeric system actor, yields a blank cell — never a crash. */
    @Test
    void anUnresolvableActorYieldsANullName() {
        stubEvents("SANCTION", List.of(event(3L, "SANCTION", "SYSTEM")));
        stubEvents("VALIDATE_SUCCESS", List.of());

        Map<Long, HandledBy> result = resolver.byApplicationId(List.of(3L));

        assertThat(result.get(3L).creditDecidedByName()).isNull();
        assertThat(result.get(3L).creditDecidedById()).isNull();
    }

    /** The collections worklist only holds loan ids, so it resolves through the loan → application hop. */
    @Test
    void resolvesByLoanIdThroughTheApplication() {
        LoanApplication app = new LoanApplication();
        app.setId(1L);
        app.setLoanId(70L);
        when(applicationRepository.findByLoanIdIn(List.of(70L))).thenReturn(List.of(app));
        stubEvents("SANCTION", List.of(event(1L, "SANCTION", "5")));
        stubEvents("VALIDATE_SUCCESS", List.of());
        when(staffDirectory.findStaff(5L)).thenReturn(Optional.of(staff(5L, "Priya Nair")));

        assertThat(resolver.byLoanId(List.of(70L)).get(70L).creditDecidedByName()).isEqualTo("Priya Nair");
    }

    /** An empty page must never reach the database — {@code in ()} is not valid SQL. */
    @Test
    void emptyInputShortCircuits() {
        assertThat(resolver.byApplicationId(List.of())).isEmpty();
        assertThat(resolver.byLoanId(List.of())).isEmpty();
    }

    /** Stub the pass whose action set contains {@code marker}; the other pass returns nothing. */
    private void stubEvents(String marker, List<ApplicationEvent> events) {
        when(eventRepository.findByApplicationIdInAndActionInOrderByAtDesc(anyCollection(),
                argThatContains(marker))).thenReturn(events);
    }

    private static Collection<String> argThatContains(String marker) {
        return org.mockito.ArgumentMatchers.argThat(actions -> actions != null && actions.contains(marker));
    }

    private ApplicationEvent event(Long applicationId, String action, String actorId) {
        ApplicationEvent e = new ApplicationEvent();
        e.setApplicationId(applicationId);
        e.setAction(action);
        e.setActorId(actorId);
        e.setAt(Instant.now());
        return e;
    }

    private StaffSummary staff(Long id, String name) {
        return new StaffSummary(id, name, "CREDIT_EXECUTIVE", true);
    }
}
