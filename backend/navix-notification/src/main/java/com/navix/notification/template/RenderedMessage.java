package com.navix.notification.template;

import com.navix.common.notification.NotificationChannel;

/** A fully-rendered message for one channel (placeholders substituted). {@code subject} is null for SMS. */
public record RenderedMessage(NotificationChannel channel, String subject, String body) {
}
