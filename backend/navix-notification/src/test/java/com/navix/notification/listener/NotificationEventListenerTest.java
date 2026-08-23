package com.navix.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.notification.event.RepaymentRejectedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.common.notification.event.SanctionLetterSignedEvent;
import com.navix.common.notification.event.SettlementRejectedEvent;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.common.storage.DocumentStoragePort;
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

    @Mock
    private DocumentStoragePort storage;

    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(dispatcher, "http://localhost:3000", storage);
    }

    private static ApplicationTransitionedEvent transition(String action, String toStatus) {
        return transition(action, toStatus, null);
    }

    private static ApplicationTransitionedEvent transition(String action, String toStatus, Instant retryFrom) {
        return new ApplicationTransitionedEvent(10L, 5L, 2L, "FROM", toStatus, action, 9L, "1", "ADMIN",
                Instant.now(), retryFrom);
    }

    /** The model the dispatcher was handed. */
    private java.util.Map<String, Object> model() {
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(any(NotificationType.class), ctx.capture());
        return ctx.getValue().model();
    }

    private NotificationType dispatched() {
        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        verify(dispatcher).dispatch(type.capture(), any(NotificationContext.class));
        return type.getValue();
    }

    /**
     * A rejection that set a cooling-off block tells the borrower the DATE they may re-apply. Without
     * it a declined borrower had no way to learn when "at this time" ends, and simply retried into
     * the block until they gave up.
     */
    @Test
    void aRejectionWithABlockCarriesTheRetryDate() {
        Instant retryFrom = Instant.parse("2026-11-20T04:30:00Z"); // 10:00 IST on the 20th
        listener.onApplicationTransitioned(transition("REJECT_LEAD", "REJECTED", retryFrom));

        assertThat(model().get("retryLine")).isEqualTo(" You can apply again on or after 20 Nov 2026.");
    }

    /** The date is rendered in IST, so a UTC instant late on the 19th is still the 20th here. */
    @Test
    void theRetryDateIsRenderedInIst() {
        listener.onApplicationTransitioned(
                transition("AUTO_REJECT_LOW_BUREAU_SCORE", "REJECTED", Instant.parse("2026-11-19T20:00:00Z")));

        assertThat(model().get("retryLine")).isEqualTo(" You can apply again on or after 20 Nov 2026.");
    }

    /**
     * No block, no sentence — and an EMPTY string rather than a null, because TemplateRenderer
     * renders an absent key as an em dash, which would leave "at this time.—" in the borrower's mail.
     */
    @Test
    void aTransitionWithNoBlockRendersAnEmptyRetryLineNotAnEmDash() {
        listener.onApplicationTransitioned(transition("KYC_APPROVE", "KYC_APPROVED"));

        assertThat(model().get("retryLine")).isEqualTo("");
    }

    @Test
    void mapsKycApprove() {
        listener.onApplicationTransitioned(transition("KYC_APPROVE", "KYC_APPROVED"));
        assertThat(dispatched()).isEqualTo(NotificationType.KYC_APPROVED);
    }

    @Test
    void mapsKycReject() {
        listener.onApplicationTransitioned(transition("KYC_REJECT", "KYC_REJECTED"));
        assertThat(dispatched()).isEqualTo(NotificationType.KYC_REJECTED);
    }

    /**
     * The engine's auto-reject actions carry the rule that fired as a suffix ("AUTO_REJECT_
     * LOW_BUREAU_SCORE"), so they can never match a switch case label — this used to fall through to
     * `default -> null` and notify nobody. They route to the same reasonless CREDIT_REJECTED a manual
     * credit rejection sends.
     */
    @Test
    void autoRejectSelfEmployedMapsToCreditRejected() {
        listener.onApplicationTransitioned(transition("AUTO_REJECT_SELF_EMPLOYED", "REJECTED"));
        assertThat(dispatched()).isEqualTo(NotificationType.CREDIT_REJECTED);
    }

    @Test
    void autoRejectLowBureauScoreMapsToCreditRejected() {
        listener.onApplicationTransitioned(transition("AUTO_REJECT_LOW_BUREAU_SCORE", "REJECTED"));
        assertThat(dispatched()).isEqualTo(NotificationType.CREDIT_REJECTED);
    }

    @Test
    void autoRejectUnknownSuffixAlsoMapsProvingItsPrefixBasedNotAHardcodedList() {
        listener.onApplicationTransitioned(transition("AUTO_REJECT_SOMETHING_NEW", "REJECTED"));
        assertThat(dispatched()).isEqualTo(NotificationType.CREDIT_REJECTED);
    }

    @Test
    void mapsHeadApproveToCreditApproved() {
        listener.onApplicationTransitioned(transition("HEAD_APPROVE", "CREDIT_HEAD_APPROVED"));
        assertThat(dispatched()).isEqualTo(NotificationType.CREDIT_APPROVED);
    }

    /**
     * The accountant disbursement hop was retired in V48, so nothing emits DISB_ACCEPT any more —
     * and a stale event replayed from the audit trail must not wake an accountant for work that no
     * longer exists.
     */
    @Test
    void retiredDisbursementActionsNotifyNobody() {
        listener.onApplicationTransitioned(transition("DISB_ACCEPT", "ACCOUNTANT_PENDING"));
        verifyNoInteractions(dispatcher);
    }

    /**
     * RETRY is the third route into DISBURSEMENT_PENDING (alongside APPLY_FAST_TRACK and
     * ACCEPT_OFFER) — it now notifies, since the audience includes ADMIN, who didn't click retry.
     */
    @Test
    void mapsRetryToTheDisbursementSignal() {
        listener.onApplicationTransitioned(transition("RETRY", "DISBURSEMENT_PENDING"));
        assertThat(dispatched()).isEqualTo(NotificationType.LOAN_APPLIED_FAST_TRACK);
    }

    @Test
    void mapsActivateToLoanDisbursedCarryingTheLoanId() {
        listener.onApplicationTransitioned(transition("ACTIVATE", "ACTIVE"));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.LOAN_DISBURSED);
        assertThat(ctx.getValue().customerId()).isEqualTo(5L);
        assertThat(ctx.getValue().loanId()).isEqualTo(2L);
    }

    @Test
    void reborrowForkPreApproved() {
        listener.onApplicationTransitioned(transition("REBORROW", "PRE_APPROVED"));
        assertThat(dispatched()).isEqualTo(NotificationType.REBORROW_PREAPPROVED);
    }

    @Test
    void reborrowForkDelinquentAutoRejects() {
        // Since V45 the delinquent reborrow fork auto-rejects instead of queuing a manual review.
        listener.onApplicationTransitioned(transition("REBORROW", "REJECTED"));
        assertThat(dispatched()).isEqualTo(NotificationType.CREDIT_REJECTED);
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
    void rejectedRepaymentNotifiesBorrowerWithAmountAndReason() {
        listener.onRepaymentRejected(new RepaymentRejectedEvent(
                2L, 5L, 88L, 50_000L, "WRONG_REFERENCE", "typo in UTR", Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.REPAYMENT_REJECTED);
        assertThat(ctx.getValue().customerId()).isEqualTo(5L);
        assertThat(ctx.getValue().model()).containsEntry("amount", "₹500");
        assertThat(ctx.getValue().model().get("reason").toString()).contains("reference").contains("typo in UTR");
    }

    @Test
    void sanctionLetterSignedFetchesAttachmentFromStorage() {
        byte[] pdf = "pdf-bytes".getBytes();
        org.mockito.Mockito.when(storage.fetch("applications/2/signed_agreement/x.pdf")).thenReturn(pdf);

        listener.onSanctionLetterSigned(new SanctionLetterSignedEvent(
                5L, 2L, 99L, "applications/2/signed_agreement/x.pdf", Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.SANCTION_LETTER_SIGNED);
        assertThat(ctx.getValue().attachments()).hasSize(1);
        assertThat(ctx.getValue().attachments().get(0).filename()).isEqualTo("sanction-letter-signed.pdf");
    }

    @Test
    void sanctionLetterSignedSendsWithoutAttachmentWhenFetchFails() {
        org.mockito.Mockito.when(storage.fetch(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("not found"));

        listener.onSanctionLetterSigned(new SanctionLetterSignedEvent(
                5L, 2L, 99L, "missing-key", Instant.now()));

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<NotificationContext> ctx = ArgumentCaptor.forClass(NotificationContext.class);
        verify(dispatcher).dispatch(type.capture(), ctx.capture());
        assertThat(type.getValue()).isEqualTo(NotificationType.SANCTION_LETTER_SIGNED);
        assertThat(ctx.getValue().attachments()).isEmpty();
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
        assertThat(ctx.getValue().model())
                .containsEntry("inviteLink", "http://localhost:3000/staff/activate?token=tok-abc");
    }

    @Test
    void staffRoleChangedMaps() {
        listener.onStaffAccount(new StaffAccountEvent(
                42L, "jane@navix.test", "Jane", "CREDIT_HEAD",
                StaffAccountEvent.ChangeType.ROLE_CHANGED, null, Instant.now()));
        assertThat(dispatched()).isEqualTo(NotificationType.STAFF_ROLE_CHANGED);
    }
}
