package com.navix.loan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navix.common.collections.CollectionCaseDirectory;
import com.navix.common.loan.ApplicationActorDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.AdminApplicationService;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.CreditBriefService;
import com.navix.loan.service.CustomerReviewService;
import com.navix.loan.service.CustomerTrustSignalsService;
import com.navix.loan.service.EligibilityService;
import com.navix.loan.service.JourneyService;
import com.navix.loan.service.MandateService;
import com.navix.loan.service.OfferService;
import com.navix.loan.service.VerificationOutreachService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The stage queue narrows a Collection Executive to their own repayment book, and a Disbursement Head
 * asking for {@code mine=true} to the files they released. Everyone else gets the queue unchanged.
 */
class ApplicationQueueScopeTest {

    private final ApplicationFlowService flow = mock(ApplicationFlowService.class);
    private final ApplicationActorDirectory actors = mock(ApplicationActorDirectory.class);
    private final CollectionCaseDirectory cases = mock(CollectionCaseDirectory.class);
    private final ApplicationController controller = new ApplicationController(flow,
            mock(CustomerReviewService.class), mock(ApplicationVerificationService.class),
            mock(VerificationOutreachService.class), mock(CreditBriefService.class),
            mock(AdminApplicationService.class), mock(JourneyService.class), mock(OfferService.class),
            mock(MandateService.class), mock(StaffDirectory.class), actors, cases,
            mock(LoanRepository.class), mock(ApplicationEventRepository.class),
            mock(EligibilityService.class), mock(CustomerTrustSignalsService.class));

    @BeforeEach
    void threeActiveLoans() {
        when(flow.byStatus(eq(ApplicationStatus.ACTIVE), any(), any(), any()))
                .thenReturn(List.of(app(1L, 101L), app(2L, 102L), app(3L, 103L)));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void aCollectionExecutiveSeesOnlyTheirAssignedLoans() {
        ActorContext.set(new CurrentActor("7", "Officer", "COLLECTION_EXECUTIVE"));
        when(cases.loanIdsAssignedTo(7L)).thenReturn(Set.of(102L));
        assertThat(ids(controller.queue(ApplicationStatus.ACTIVE, null, null, null, null).getData()))
                .containsExactly(2L);
    }

    @Test
    void anAdminWorkingAsCollectionExecutiveIsScopedToo() {
        ActorContext.set(new CurrentActor("9", "Admin", "ADMIN", "COLLECTION_EXECUTIVE"));
        when(cases.loanIdsAssignedTo(9L)).thenReturn(Set.of());
        assertThat(controller.queue(ApplicationStatus.ACTIVE, null, null, null, null).getData()).isEmpty();
    }

    @Test
    void aDisbursementHeadAskingForMineSeesOnlyWhatTheyReleased() {
        ActorContext.set(new CurrentActor("5", "Head", "DISBURSEMENT_HEAD"));
        when(actors.byApplicationId(anyCollection())).thenReturn(Map.of(
                1L, new ApplicationActorDirectory.HandledBy(null, null, 5L, "Head"),
                3L, new ApplicationActorDirectory.HandledBy(null, null, 6L, "Other")));
        assertThat(ids(controller.queue(ApplicationStatus.ACTIVE, null, null, null, true).getData()))
                .containsExactly(1L);
        // Without mine=true the Head's desk is the whole queue.
        assertThat(controller.queue(ApplicationStatus.ACTIVE, null, null, null, null).getData()).hasSize(3);
    }

    @Test
    void otherRolesAreUnaffectedByMine() {
        ActorContext.set(new CurrentActor("1", "Accountant", "ACCOUNTANT"));
        assertThat(controller.queue(ApplicationStatus.ACTIVE, null, null, null, true).getData()).hasSize(3);
    }

    private static LoanApplication app(Long id, Long loanId) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setLoanId(loanId);
        a.setStatus(ApplicationStatus.ACTIVE);
        return a;
    }

    private static List<Long> ids(List<ApplicationView> views) {
        return views.stream().map(ApplicationView::id).toList();
    }
}
