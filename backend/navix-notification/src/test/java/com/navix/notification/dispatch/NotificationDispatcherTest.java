package com.navix.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.loan.BorrowerContactDirectory;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.notification.audience.AudienceResolver;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.channel.ChannelSender;
import com.navix.notification.channel.DeliveryOutcome;
import com.navix.notification.channel.DeliveryStatus;
import com.navix.notification.entity.Notification;
import com.navix.notification.entity.NotificationDelivery;
import com.navix.notification.repository.NotificationDeliveryRepository;
import com.navix.notification.repository.NotificationRepository;
import com.navix.notification.template.NotificationTemplates;
import com.navix.notification.template.RenderedMessage;
import com.navix.notification.template.TemplateRenderer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The dispatcher fans out one row per recipient, records one delivery per channel, and isolates
 * errors so a single channel/recipient failure never sinks the rest. Uses a real renderer/templates
 * with mocked repos + fake senders (one of which throws).
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private AudienceResolver audienceResolver;
    @Mock
    private NotificationRepository notificationRepo;
    @Mock
    private NotificationDeliveryRepository deliveryRepo;
    @Mock
    private LoanDirectory loanDirectory;
    @Mock
    private com.navix.common.loan.BorrowerPreferenceDirectory borrowerPreferences;
    @Mock
    private BorrowerContactDirectory borrowerContacts;

    private NotificationDispatcher dispatcher;

    /** IN_APP + EMAIL succeed; SMS throws — to prove one channel failure is isolated. */
    private static ChannelSender okSender(NotificationChannel ch) {
        return new ChannelSender() {
            @Override public NotificationChannel channel() { return ch; }
            @Override public DeliveryOutcome send(RenderedMessage m, ContactInfo r) { return DeliveryOutcome.sent("ref-" + ch); }
        };
    }

    private static ChannelSender throwingSender(NotificationChannel ch) {
        return new ChannelSender() {
            @Override public NotificationChannel channel() { return ch; }
            @Override public DeliveryOutcome send(RenderedMessage m, ContactInfo r) { throw new RuntimeException("boom"); }
        };
    }

    @BeforeEach
    void setUp() {
        TemplateRenderer renderer = new TemplateRenderer(new NotificationTemplates());
        lenient().when(borrowerPreferences.optedOutChannels(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
        // Defensive default: most tests never reach the customerId fallback (either no loan-money model
        // is in play, or the loan lookup already supplied customerName) — this just prevents an
        // unstubbed-mock NPE for the ones that do reach it without caring about the resolved value.
        lenient().when(borrowerContacts.borrowerContact(any())).thenReturn(Optional.empty());
        dispatcher = new NotificationDispatcher(renderer, audienceResolver, notificationRepo, deliveryRepo,
                loanDirectory, borrowerPreferences, borrowerContacts,
                List.of(okSender(NotificationChannel.IN_APP),
                        throwingSender(NotificationChannel.SMS),
                        okSender(NotificationChannel.EMAIL)));

        AtomicLong seq = new AtomicLong(1);
        // lenient: the no-recipient test never saves, so this stub may go unused.
        lenient().when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            if (n.getId() == null) {
                n.setId(seq.getAndIncrement());
            }
            return n;
        });
    }

    private static ContactInfo borrower(long id) {
        return new ContactInfo(RecipientType.BORROWER, id, "Asha", "asha@x.test", "9876500000", "BORROWER");
    }

    private static ContactInfo staff(long id) {
        return new ContactInfo(RecipientType.STAFF, id, "Staff " + id, "s" + id + "@navix.test", null, "CREDIT_EXECUTIVE");
    }

    @Test
    void persistsOneNotificationAndDeliveryPerRecipientForSingleChannelType() {
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of(staff(1), staff(2)));

        // LOAN_APPLIED has IN_APP only → one notification + one delivery per recipient. (KYC_SUBMITTED
        // gained an EMAIL template alongside ADMIN in its audience — no longer single-channel.)
        dispatcher.dispatch(NotificationType.LOAN_APPLIED, NotificationContext.builder().applicationId(10L).build());

        ArgumentCaptor<Notification> notif = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo, times(2)).save(notif.capture());
        assertThat(notif.getAllValues()).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.LOAN_APPLIED);
            assertThat(n.getTitle()).isEqualTo("New application to review");
            assertThat(n.isInApp()).isTrue();
            assertThat(n.getApplicationId()).isEqualTo(10L);
        });
        verify(deliveryRepo, times(2)).save(any(NotificationDelivery.class));
    }

    @Test
    void isolatesAFailingChannelAcrossAllChannelsOfOneRecipient() {
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of(borrower(7)));
        when(loanDirectory.findLoan(anyLong())).thenReturn(Optional.empty());

        // LOAN_DISBURSED fans across IN_APP + SMS + EMAIL; SMS throws but must not abort the others.
        dispatcher.dispatch(NotificationType.LOAN_DISBURSED,
                NotificationContext.builder().customerId(7L).loanId(2L).build());

        verify(notificationRepo, times(1)).save(any(Notification.class));
        ArgumentCaptor<NotificationDelivery> cap = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(3)).save(cap.capture());
        List<NotificationDelivery> deliveries = cap.getAllValues();

        assertThat(deliveries).anySatisfy(d -> {
            assertThat(d.getChannel()).isEqualTo(NotificationChannel.SMS);
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(d.getError()).contains("boom");
        });
        assertThat(deliveries).filteredOn(d -> d.getStatus() == DeliveryStatus.SENT).hasSize(2);
    }

    @Test
    void noRecipientsIsANoOp() {
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of());

        dispatcher.dispatch(NotificationType.KYC_SUBMITTED, NotificationContext.builder().applicationId(10L).build());

        verify(notificationRepo, times(0)).save(any());
        verify(deliveryRepo, times(0)).save(any());
    }

    @Test
    void suppressesAChannelTheBorrowerOptedOutOf() {
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of(borrower(7)));
        when(loanDirectory.findLoan(anyLong())).thenReturn(Optional.empty());
        // Borrower opted out of SMS → IN_APP + EMAIL still send; SMS records SKIPPED=OPTED_OUT (not sent).
        when(borrowerPreferences.optedOutChannels(7L)).thenReturn(java.util.Set.of(NotificationChannel.SMS));

        dispatcher.dispatch(NotificationType.LOAN_DISBURSED,
                NotificationContext.builder().customerId(7L).loanId(2L).build());

        ArgumentCaptor<NotificationDelivery> cap = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(3)).save(cap.capture());
        List<NotificationDelivery> deliveries = cap.getAllValues();
        // SMS is suppressed (opted out) — never reaches the throwing sender, recorded as SKIPPED.
        assertThat(deliveries).anySatisfy(d -> {
            assertThat(d.getChannel()).isEqualTo(NotificationChannel.SMS);
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SKIPPED);
            assertThat(d.getError()).isEqualTo("OPTED_OUT");
        });
        // IN_APP + EMAIL still went out.
        assertThat(deliveries).filteredOn(d -> d.getStatus() == DeliveryStatus.SENT).hasSize(2);
    }

    /** An EMAIL sender that captures the rendered message instead of just acking it, for body assertions. */
    private static ChannelSender capturingEmailSender(AtomicReference<RenderedMessage> sink) {
        return new ChannelSender() {
            @Override public NotificationChannel channel() { return NotificationChannel.EMAIL; }
            @Override public DeliveryOutcome send(RenderedMessage m, ContactInfo r) {
                sink.set(m);
                return DeliveryOutcome.sent("ref-email");
            }
        };
    }

    @Test
    void loanScopedContextWithNoApplicationIdPicksItUpFromLoanSummary() {
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of(borrower(7)));
        LoanSummary loan = new LoanSummary(2L, 7L, 55L, "ACTIVE", 1_000_000L, 882_000L, 1_270_000L,
                1_270_000L, null, null, "Priya Singh", "ABCDE1234F", null, null, null, null);
        when(loanDirectory.findLoan(2L)).thenReturn(Optional.of(loan));

        AtomicReference<RenderedMessage> captured = new AtomicReference<>();
        NotificationDispatcher localDispatcher = new NotificationDispatcher(
                new TemplateRenderer(new NotificationTemplates()), audienceResolver, notificationRepo,
                deliveryRepo, loanDirectory, borrowerPreferences, borrowerContacts,
                List.of(okSender(NotificationChannel.IN_APP), okSender(NotificationChannel.SMS),
                        capturingEmailSender(captured)));

        // The context itself carries no applicationId — LOAN_DISBURSED's email body has "#{applicationId}"
        // only because baseModel backfills it from the LoanSummary found via ctx.loanId().
        localDispatcher.dispatch(NotificationType.LOAN_DISBURSED,
                NotificationContext.builder().customerId(7L).loanId(2L).build());

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().body()).contains("#55");
    }

    @Test
    void customerNameIsPopulatedForAStaffRecipientWhileNameStaysTheStaffMembersName() {
        // KYC_SUBMITTED is application-scoped (no loan yet) — customerName can only come from the
        // ctx.customerId() fallback via BorrowerContactDirectory, not from a LoanSummary.
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of(staff(3)));
        when(borrowerContacts.borrowerContact(7L)).thenReturn(Optional.of(
                new ContactInfo(RecipientType.BORROWER, 7L, "Priya Singh", "priya@x.test", "9876500000", "BORROWER")));

        AtomicReference<RenderedMessage> captured = new AtomicReference<>();
        NotificationDispatcher localDispatcher = new NotificationDispatcher(
                new TemplateRenderer(new NotificationTemplates()), audienceResolver, notificationRepo,
                deliveryRepo, loanDirectory, borrowerPreferences, borrowerContacts,
                List.of(okSender(NotificationChannel.IN_APP), okSender(NotificationChannel.SMS),
                        capturingEmailSender(captured)));

        localDispatcher.dispatch(NotificationType.KYC_SUBMITTED,
                NotificationContext.builder().applicationId(42L).customerId(7L).build());

        verify(borrowerContacts).borrowerContact(7L);
        assertThat(captured.get()).isNotNull();
        // {name} is the STAFF recipient (staff(3) -> "Staff 3"), {customerName} is the resolved borrower.
        assertThat(captured.get().body()).contains("Hi Staff 3,").contains("Priya Singh has submitted");
    }
}
