package com.navix.notification.channel;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.template.RenderedMessage;

/**
 * Transport for one {@link NotificationChannel}. Implementations <b>never throw</b> — a transport
 * failure is returned as a {@link DeliveryOutcome#failed} so one recipient/channel failure can never
 * abort the rest of a fan-out (error isolation lives in the dispatcher's loop too, belt-and-suspenders).
 */
public interface ChannelSender {

    NotificationChannel channel();

    DeliveryOutcome send(RenderedMessage message, ContactInfo recipient);
}
