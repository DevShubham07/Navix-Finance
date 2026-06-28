package com.navix.notification.channel;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.template.RenderedMessage;
import org.springframework.stereotype.Component;

/**
 * In-app "transport" — a no-op: the persisted {@code Notification} row <i>is</i> the inbox, so there
 * is nothing to send. The delivery row simply records it as {@code SENT} for a uniform audit trail.
 */
@Component
public class InAppSender implements ChannelSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message, ContactInfo recipient) {
        return DeliveryOutcome.sent(null);
    }
}
