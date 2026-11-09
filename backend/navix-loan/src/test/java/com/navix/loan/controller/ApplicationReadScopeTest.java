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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The by-id application reads follow the customer book: a scoped staff role gets a 404 for an
 * application outside it (the same answer {@code /api/customers/{id}} gives), and a borrower's own
 * read drops the staff-only fields.
 */
class ApplicationReadScopeTest {

    private final ApplicationFlowService flow = mock(ApplicationFlowService.class);
    private final CustomerReviewService review = mock(CustomerReviewService.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final ApplicationController controller = new ApplicationController(flow, review,
            mock(ApplicationVerificationService.class), mock(VerificationOutreachService.class),
            mock(CreditBriefService.class), mock(AdminApplicationService.class), mock(JourneyService.class),
            mock(OfferService.class), mock(MandateService.class), mock(StaffDirectory.class),
            mock(ApplicationActorDirectory.class), mock(CollectionCaseDirectory.class),
            mock(LoanRepository.class), mock(ApplicationEventRepository.class),
            mock(EligibilityService.class), mock(CustomerTrustSignalsService.class), customers);

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void scopedStaffGetsA404ForAnApplicationOutsideTheirBook() {
        ActorContext.set(new CurrentActor("15", "Tara", "TELECALLER"));
        when(flow.customerIdOf(5L)).thenReturn(50L);
        doThrow(new ResourceNotFoundException("Customer", "50")).when(customers).assertVisible(50L);

        assertThatThrownBy(() -> controller.getProfile(5L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.listDocuments(5L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.events(5L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.creditBrief(5L)).isInstanceOf(ResourceNotFoundException.class);
        verify(review, never()).getProfile(any());
    }

    @Test
    void anApplicationInsideTheBookStillReads() {
        ActorContext.set(new CurrentActor("7", "Ravi", "CREDIT_EXECUTIVE"));
        when(flow.customerIdOf(6L)).thenReturn(60L);
        when(flow.eventViews(6L)).thenReturn(List.of());

        assertThat(controller.events(6L).getData()).isEmpty();
        verify(customers).assertVisible(60L);
    }

    @Test
    void borrowerEventsCarryTheTransitionButNeitherRemarksNorStaffIdentity() {
        ActorContext.set(new CurrentActor("21", "Asha", "BORROWER"));
        LoanApplication app = new LoanApplication();
        app.setCustomerId(21L);
        when(flow.get(5L)).thenReturn(app);
        when(flow.eventViews(5L)).thenReturn(List.of(new EventView(1L, ApplicationStatus.CREDIT_EXEC_PENDING,
                ApplicationStatus.REJECTED, "7", "CREDIT_EXECUTIVE", "CREDIT_EXECUTIVE", "Ravi", "REJECT_LEAD",
                "Salary slip looks edited", Instant.parse("2026-11-01T00:00:00Z"))));

        EventView e = controller.events(5L).getData().get(0);

        assertThat(e.toStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(e.action()).isEqualTo("REJECT_LEAD");
        assertThat(e.at()).isNotNull();
        assertThat(e.notes()).isNull();
        assertThat(e.actorId()).isNull();
        assertThat(e.actorName()).isNull();
        assertThat(e.actorRole()).isNull();
        assertThat(e.actingRole()).isNull();
        verify(customers, never()).assertVisible(any());
    }

    @Test
    void borrowerApplicationViewDropsStaffOnlyFieldsButStaffKeepsThem() {
        LoanApplication app = new LoanApplication();
        app.setId(5L);
        app.setCustomerId(21L);
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setAssignedExecutiveId(7L);
        app.setSanctionRemarks("Approved with a lower ceiling: thin bureau file");
        app.setPendingReason("Ask for a fresh payslip");
        app.setMarkedPendingAt(Instant.now());
        app.setSanctionedAmountPaise(500_000L);

        ActorContext.set(new CurrentActor("21", "Asha", "BORROWER"));
        ApplicationView borrower = ApplicationView.of(app).forReader();
        assertThat(borrower.assignedExecutiveId()).isNull();
        assertThat(borrower.sanctionRemarks()).isNull();
        assertThat(borrower.pendingReason()).isNull();
        assertThat(borrower.markedPendingAt()).isNull();
        assertThat(borrower.sanctionedAmountPaise()).isEqualTo(500_000L);

        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        ApplicationView staff = ApplicationView.of(app).forReader();
        assertThat(staff.assignedExecutiveId()).isEqualTo(7L);
        assertThat(staff.sanctionRemarks()).isNotNull();
        assertThat(staff.pendingReason()).isNotNull();
    }
}
