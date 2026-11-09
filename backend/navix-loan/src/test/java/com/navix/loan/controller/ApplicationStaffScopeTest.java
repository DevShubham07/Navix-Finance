package com.navix.loan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.collections.CollectionCaseDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.loan.ApplicationActorDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.ApplicationDtos.EventView;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.AdminApplicationService;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.CreditBriefService;
import com.navix.loan.service.CustomerReviewService;
import com.navix.loan.service.CustomerService;
import com.navix.loan.service.CustomerTrustSignalsService;
import com.navix.loan.service.EligibilityService;
import com.navix.loan.service.JourneyService;
import com.navix.loan.service.MandateService;
import com.navix.loan.service.OfferService;
import com.navix.loan.service.VerificationOutreachService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Queue, reminder and mandate routes follow the customer book; the queue refuses a telecaller outright. */
class ApplicationStaffScopeTest {

    private final ApplicationFlowService flow = mock(ApplicationFlowService.class);
    private final CustomerReviewService review = mock(CustomerReviewService.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final ApplicationVerificationService verification = mock(ApplicationVerificationService.class);
    private final MandateService mandates = mock(MandateService.class);
    private final ApplicationController controller = new ApplicationController(flow, review,
            verification, mock(VerificationOutreachService.class),
            mock(CreditBriefService.class), mock(AdminApplicationService.class), mock(JourneyService.class),
            mock(OfferService.class), mandates, mock(StaffDirectory.class),
            mock(ApplicationActorDirectory.class), mock(CollectionCaseDirectory.class),
            mock(LoanRepository.class), mock(ApplicationEventRepository.class),
            mock(EligibilityService.class), mock(CustomerTrustSignalsService.class), customers);

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static LoanApplication app(long id, long customerId) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        return a;
    }

    @Test
    void queueDropsRowsOutsideTheBookAndRefusesATelecaller() {
        when(flow.byStatus(any(), any(), any(), any())).thenReturn(List.of(app(1, 10), app(2, 20)));
        when(customers.visibleAmong(any())).thenReturn(Set.of());

        ActorContext.set(new CurrentActor("7", "Ravi", "CREDIT_HEAD"));
        assertThat(controller.queue(ApplicationStatus.KYC_PENDING, null, null, null, null).getData()).isEmpty();
        verify(customers).visibleAmong(List.of(10L, 20L));

        ActorContext.set(new CurrentActor("15", "Tara", "TELECALLER"));
        assertThatThrownBy(() -> controller.queue(ApplicationStatus.KYC_PENDING, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
    }

    @Test
    void sendReminderAndEveryMandateRouteAre404OutsideTheBook() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        when(flow.customerIdOf(5L)).thenReturn(50L);
        doThrow(new ResourceNotFoundException("Customer", "50")).when(customers).assertVisible(50L);

        List<org.junit.jupiter.api.function.ThrowingSupplier<Object>> calls = List.of(
                () -> controller.sendReminder(5L),
                () -> controller.mandateHistory(5L),
                () -> controller.mandateRefresh(5L),
                () -> controller.mandateCeiling(5L),
                () -> controller.mandateInvite(5L),
                () -> controller.mandateCancel(5L, 1L, null),
                () -> controller.mandateCancelActive(5L, 1L,
                        new ApplicationController.CancelActiveMandateRequest(null, null)),
                () -> controller.mandateChangeAccount(5L,
                        new ApplicationController.ChangeMandateAccountRequest("1", "X", null)));
        for (var call : calls) {
            assertThatThrownBy(call::get).isInstanceOf(ResourceNotFoundException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(verification, mandates);
    }
}
