package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.TelecallingDtos.TelecallingView;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerOwner;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.CustomerOwnerRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two admin registers this service backs — the telecalling queue and the all-applications
 * register — used to pay one completeness lookup (plus a re-apply chain walk) per row. Over the live
 * book that was ~9.7k round trips in a single request and a 13-15s response, polled every 15s. These
 * tests pin the two properties that fixed it: the queue is filtered in SQL rather than by loading the
 * whole table, and completeness is resolved for the whole page in one batched call.
 */
@ExtendWith(MockitoExtension.class)
class AdminApplicationServiceTest {

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private ApplicationVerificationService verification;
    @Mock private ApplicationRejectionRepository rejectionRepository;
    @Mock private ApplicationEventRepository eventRepository;
    @Mock private CustomerOwnerRepository ownerRepository;
    @Mock private BureauStateService bureauStateService;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;

    private AdminApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AdminApplicationService(applicationRepository, profileRepository, verification,
                rejectionRepository, eventRepository, ownerRepository, bureauStateService, staffDirectory);
        ActorContext.set(new CurrentActor("1", "Meera", "ADMIN"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void telecallingFiltersInSqlAndResolvesCompletenessInOneBatchedCall() {
        LoanApplication stale = app(1L, 9000001L, ApplicationStatus.DRAFT);
        LoanApplication fresh = app(2L, 9000002L, ApplicationStatus.KYC_PENDING);
        when(applicationRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of(stale, fresh));
        when(profileRepository.findByApplicationIdIn(anyCollection()))
                .thenReturn(List.of(profile(1L, "Asha Rao", "9819000001"), profile(2L, "Bilal Khan", "9819000002")));
        Instant now = Instant.now();
        when(eventRepository.findByApplicationIdInOrderByAtDesc(any())).thenReturn(List.of(
                event(1L, now.minus(9, ChronoUnit.DAYS)),
                event(2L, now.minus(1, ChronoUnit.DAYS))));
        when(ownerRepository.findAllById(anyCollection())).thenReturn(List.of(owner(9000002L, 13L)));
        when(verification.requiredPassedCounts(anyCollection())).thenReturn(Map.of(1L, 1, 2L, 4));

        List<TelecallingView> rows = service.listForTelecalling();

        // Stalest first, each row carrying the completeness the batched call resolved for it.
        assertThat(rows).extracting(TelecallingView::id).containsExactly(1L, 2L);
        assertThat(rows.get(0).stepsCompleted()).isEqualTo(1);
        assertThat(rows.get(0).staleDays()).isEqualTo(9L);
        assertThat(rows.get(1).stepsCompleted()).isEqualTo(4);
        assertThat(rows.get(1).ownerStaffId()).isEqualTo(13L);
        assertThat(rows.get(0).stepsRequired()).isEqualTo(ApplicationVerificationService.requiredCount());

        // The whole table is never loaded, and completeness is never resolved one row at a time.
        verify(applicationRepository, never()).findAll();
        verify(verification, never()).requiredPassedCount(any());
    }

    @Test
    void telecallingExcludesEverythingThatReachedSanction() {
        when(applicationRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of());

        assertThat(service.listForTelecalling()).isEmpty();

        // The exclusion set is what the SQL is asked for — not a post-filter.
        verify(applicationRepository).findByStatusNotIn(argThatContainsSanctionedStates());
        verify(verification, never()).requiredPassedCounts(anyCollection());
    }

    @Test
    void telecallingRejectsRolesThatAreNotTelecallerOrAdmin() {
        ActorContext.set(new CurrentActor("7", "Deepa", "ACCOUNTANT"));

        assertThatThrownBy(() -> service.listForTelecalling())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TELECALLER");
    }

    @Test
    void telecallingIsOpenToTelecallers() {
        ActorContext.set(new CurrentActor("13", "Tara", "TELECALLER"));
        when(applicationRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of());

        assertThat(service.listForTelecalling()).isEmpty();
    }

    @Test
    void allApplicationsResolvesCompletenessInOneBatchedCallAndIsAdminOnly() {
        LoanApplication one = app(1L, 9000001L, ApplicationStatus.KYC_PENDING);
        when(applicationRepository.findAll()).thenReturn(List.of(one));
        CustomerProfile p = profile(1L, "Asha Rao", "9819000001");
        p.setTermsAcceptedAt(Instant.now());
        when(profileRepository.findByApplicationIdIn(anyCollection())).thenReturn(List.of(p));
        when(bureauStateService.states(anyCollection())).thenReturn(Map.of());
        when(eventRepository.findByApplicationIdInOrderByAtDesc(any())).thenReturn(List.of());
        when(verification.requiredPassedCounts(anyCollection()))
                .thenReturn(Map.of(1L, ApplicationVerificationService.requiredCount()));

        var rows = service.listAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).stepsCompleted()).isEqualTo(ApplicationVerificationService.requiredCount());
        assertThat(rows.get(0).complete()).isTrue();
        verify(verification, never()).requiredPassedCount(any());

        ActorContext.set(new CurrentActor("13", "Tara", "TELECALLER"));
        assertThatThrownBy(() -> service.listAll()).isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------ fixtures

    private static java.util.Collection<ApplicationStatus> argThatContainsSanctionedStates() {
        return org.mockito.ArgumentMatchers.argThat(statuses ->
                statuses.contains(ApplicationStatus.SANCTIONED)
                        && statuses.contains(ApplicationStatus.ACTIVE)
                        && statuses.contains(ApplicationStatus.CLOSED)
                        && !statuses.contains(ApplicationStatus.DRAFT)
                        && !statuses.contains(ApplicationStatus.KYC_PENDING));
    }

    private static LoanApplication app(Long id, Long customerId, ApplicationStatus status) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setStatus(status);
        return a;
    }

    private static CustomerProfile profile(Long appId, String name, String mobile) {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(appId);
        p.setFullName(name);
        p.setMobile(mobile);
        return p;
    }

    private static ApplicationEvent event(Long appId, Instant at) {
        ApplicationEvent e = new ApplicationEvent();
        e.setApplicationId(appId);
        e.setAt(at);
        return e;
    }

    private static CustomerOwner owner(Long customerId, Long staffId) {
        CustomerOwner o = new CustomerOwner();
        o.setCustomerId(customerId);
        o.setOwnerStaffId(staffId);
        return o;
    }
}
