package com.navix.notification.ses;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.channel.DeliveryStatus;
import com.navix.notification.entity.NotificationDelivery;
import com.navix.notification.repository.NotificationDeliveryRepository;
import com.navix.notification.suppression.EmailSuppressionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Parses SES bounce/complaint events (raw + SNS-wrapped) and drives suppression + delivery marking. */
class SesEventSqsListenerTest {

    private final EmailSuppressionService suppression = org.mockito.Mockito.mock(EmailSuppressionService.class);
    private final NotificationDeliveryRepository deliveryRepo =
            org.mockito.Mockito.mock(NotificationDeliveryRepository.class);
    private final SesEventSqsListener listener =
            new SesEventSqsListener(new ObjectMapper(), suppression, deliveryRepo);

    @Test
    void permanentBounceSuppressesAndMarksDelivery() {
        String json = """
            {
              "eventType": "Bounce",
              "mail": { "messageId": "msg-abc" },
              "bounce": {
                "bounceType": "Permanent",
                "bounceSubType": "General",
                "bouncedRecipients": [
                  { "emailAddress": "bounce@simulator.amazonses.com", "diagnosticCode": "smtp; 550 user unknown" }
                ]
              }
            }
            """;
        NotificationDelivery row = new NotificationDelivery();
        when(deliveryRepo.findByProviderRefAndChannel("msg-abc", NotificationChannel.EMAIL))
                .thenReturn(List.of(row));

        listener.onSesEvent(json);

        verify(suppression).suppress(eq("bounce@simulator.amazonses.com"), eq("BOUNCE"),
                eq("General"), eq("msg-abc"), eq("smtp; 550 user unknown"));
        verify(deliveryRepo).saveAll(any());
        org.assertj.core.api.Assertions.assertThat(row.getStatus()).isEqualTo(DeliveryStatus.BOUNCED);
        org.assertj.core.api.Assertions.assertThat(row.getError()).isEqualTo("BOUNCE:General");
    }

    @Test
    void transientBounceIsNotSuppressed() {
        String json = """
            {
              "eventType": "Bounce",
              "mail": { "messageId": "msg-t" },
              "bounce": {
                "bounceType": "Transient",
                "bounceSubType": "MailboxFull",
                "bouncedRecipients": [ { "emailAddress": "full@x.test" } ]
              }
            }
            """;

        listener.onSesEvent(json);

        verify(suppression, never()).suppress(any(), any(), any(), any(), any());
        verify(deliveryRepo, never()).saveAll(any());
    }

    @Test
    void complaintSuppressesEachRecipient() {
        String json = """
            {
              "eventType": "Complaint",
              "mail": { "messageId": "msg-cmp" },
              "complaint": {
                "complaintFeedbackType": "abuse",
                "complainedRecipients": [ { "emailAddress": "complaint@simulator.amazonses.com" } ]
              }
            }
            """;
        when(deliveryRepo.findByProviderRefAndChannel("msg-cmp", NotificationChannel.EMAIL))
                .thenReturn(List.of());

        listener.onSesEvent(json);

        verify(suppression).suppress(eq("complaint@simulator.amazonses.com"), eq("COMPLAINT"),
                eq("abuse"), eq("msg-cmp"), isNull());
    }

    @Test
    void unwrapsSnsEnvelope() {
        String inner = "{\\\"eventType\\\":\\\"Complaint\\\",\\\"mail\\\":{\\\"messageId\\\":\\\"msg-w\\\"},"
                + "\\\"complaint\\\":{\\\"complainedRecipients\\\":[{\\\"emailAddress\\\":\\\"w@x.test\\\"}]}}";
        String wrapped = "{ \"Type\": \"Notification\", \"Message\": \"" + inner + "\" }";

        listener.onSesEvent(wrapped);

        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(suppression).suppress(email.capture(), eq("COMPLAINT"), any(), eq("msg-w"), any());
        org.assertj.core.api.Assertions.assertThat(email.getValue()).isEqualTo("w@x.test");
    }

    @Test
    void ignoresUnknownEventType() {
        listener.onSesEvent("{ \"eventType\": \"Delivery\", \"mail\": { \"messageId\": \"m\" } }");

        verifyNoInteractions(suppression);
    }

    @Test
    void swallowsMalformedPayload() {
        listener.onSesEvent("not json at all");

        verifyNoInteractions(suppression);
    }
}
