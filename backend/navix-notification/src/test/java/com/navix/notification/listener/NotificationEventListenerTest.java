package com.navix.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.notification.event.RepaymentRejectedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.common.notification.event.SettlementRejectedEvent;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.dispatch.NotificationContext;
import com.navix.notification.dispatch.NotificationDispatcher;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The event→type mapping: transition actions, the reborrow fork, the verified-repayment dedup, IAM. */
@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationDispatcher dispatcher;

    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(dispatcher);
    }

    private static ApplicationTransitionedEvent transition(String action, String toStatus) {
        return new ApplicationTransitionedEvent(10L, 5L, 2L, "FROM", toStatus, action, 9L, "1", "ADMIN", Instant.now());
    }

    private NotificationType dispatched() {
        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        verify(dispatcher).dispatch(type.capture(), any(NotificationContext.class));
        return type.getValue();
    }

    @Test
    void mapsKycApprove() {
        listener.onApplicationTransitioned(transition("KYC_APPROVE", "KYC_APPROVED"));
        assertThat(dispatched()).isEqualTo(NotificationType.KYC_APPROVED);
    }

    @Test
    void mapsHeadApproveToCreditApproved() {
        listener.onApplicationTransitioned(transition("HEAD_APPROVE", "CREDIT_HEAD_APPROVED"));
        assertThat(dispatched()).isEqualTo(NotificationType.CREDIT_APPROVED);
    }

    @Test
    void mapsDisbAcceptToAccountantPending() {
        listener.onApplicationTransitioned(transition("DISB_ACCEPT", "ACCOUNTANT_PENDING"));
        assertThat(dispatched()).isEqualTo(NotificationType.DISBURSEMENT_PENDING_ACCOUNTANT);
    }

    @Test
    void mapsActivateToLoanDisbursedCarryingTheLoanId() {
        listener.onApplicationTransitioned(transition("ACTIVATE", "ACTIVE"));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.LOAN_DISBURSED);
        assertThat(ctx.getValue().applicantId()).isEqualTo(5L);
        assertThat(ctx.getValue().loanId()).isEqualTo(2L);
    }

    @Test
    void reborrowForkPreApproved() {
        listener.onApplicationTransitioned(transition("REBORROW", "PRE_APPROVED"));
        assertThat(dispatched()).isEqualTo(NotificationType.REBORROW_PREAPPROVED);
    }

    @Test
    void reborrowForkReviewPending() {
        listener.onApplicationTransitioned(transition("REBORROW", "REVIEW_PENDING"));
        assertThat(dispatched()).isEqualTo(NotificationType.REBORROW_REVIEW_PENDING);
    }

    @Test
    void autoRoutedActionsAreNoOps() {
        listener.onApplicationTransitioned(transition("AUTO_ROUTE", "CREDIT_HEAD_PENDING"));
        listener.onApplicationTransitioned(transition("CREATE", "DRAFT"));
        listener.onApplicationTransitioned(transition("VALIDATE_SUCCESS", "DISBURSED"));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void verifiedRepaymentThatClosedTheLoanIsSkipped() {
        // LOAN_CLOSED (the REPAID transition) already covers the closing payment — avoid a double.
        listener.onRepaymentVerified(new RepaymentVerifiedEvent(2L, 5L, 88L, 50_000L, true, Instant.now()));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void verifiedRepaymentMidLoanNotifiesWithAmount() {
        listener.onRepaymentVerified(new RepaymentVerifiedEvent(2L, 5L, 88L, 50_000L, false, Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.REPAYMENT_VERIFIED);
        assertThat(ctx.getValue().model()).containsEntry("amount", "₹500");
    }

    @Test
    void rejectedRepaymentNotifiesBorrowerWithAmount() {
        listener.onRepaymentRejected(new RepaymentRejectedEvent(2L, 5L, 88L, 50_000L, Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.REPAYMENT_REJECTED);
        assertThat(ctx.getValue().applicantId()).isEqualTo(5L);
        assertThat(ctx.getValue().model()).containsEntry("amount", "₹500");
    }

    @Test
    void rejectedSettlementNotifiesTheProposer() {
        listener.onSettlementRejected(new SettlementRejectedEvent(
                UUID.randomUUID(), UUID.randomUUID(), 2L, 5L, 700_000L, 9L, Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.SETTLEMENT_REJECTED);
        // Targets the specific staff member who proposed it (not a role fan-out).
        assertThat(ctx.getValue().staffSubjectId()).isEqualTo(9L);
        assertThat(ctx.getValue().model()).containsKey("settlementAmount");
    }

    @Test
    void staffInvitedMapsAndCarriesTokenAndExplicitSubject() {
        listener.onStaffAccount(new StaffAccountEvent(
                null, "new@navix.test", "New Hire", "ACCOUNTANT",
                StaffAccountEvent.ChangeType.INVITED, "tok-abc", Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.STAFF_INVITED);
        // No staff row yet → id 0 sentinel + an explicit contact carrying the email.
        assertThat(ctx.getValue().staffSubjectId()).isEqualTo(0L);
        assertThat(ctx.getValue().explicitStaffSubject().email()).isEqualTo("new@navix.test");
        assertThat(ctx.getValue().model()).containsEntry("inviteLink", "/staff/accept-invite?token=tok-abc");
    }

    @Test
    void staffRoleChangedMaps() {
        listener.onStaffAccount(new StaffAccountEvent(
                42L, "jane@navix.test", "Jane", "CREDIT_HEAD",
                StaffAccountEvent.ChangeType.ROLE_CHANGED, null, Instant.now()));
        assertThat(dispatched()).isEqualTo(NotificationType.STAFF_ROLE_CHANGED);
    }
}
