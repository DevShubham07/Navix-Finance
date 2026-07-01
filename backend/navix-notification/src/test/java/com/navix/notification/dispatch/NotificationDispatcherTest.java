package com.navix.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.loan.LoanDirectory;
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
        dispatcher = new NotificationDispatcher(renderer, audienceResolver, notificationRepo, deliveryRepo,
                loanDirectory, borrowerPreferences,
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
        return new ContactInfo(RecipientType.STAFF, id, "Staff " + id, "s" + id + "@navix.test", null, "KYC_APPROVER");
    }

    @Test
    void persistsOneNotificationAndDeliveryPerRecipientForSingleChannelType() {
        when(audienceResolver.resolve(any(), any())).thenReturn(List.of(staff(1), staff(2)));

        // KYC_SUBMITTED has IN_APP only → one notification + one delivery per recipient.
        dispatcher.dispatch(NotificationType.KYC_SUBMITTED, NotificationContext.builder().applicationId(10L).build());

        ArgumentCaptor<Notification> notif = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo, times(2)).save(notif.capture());
        assertThat(notif.getAllValues()).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.KYC_SUBMITTED);
            assertThat(n.getTitle()).isEqualTo("New KYC to review");
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
}
