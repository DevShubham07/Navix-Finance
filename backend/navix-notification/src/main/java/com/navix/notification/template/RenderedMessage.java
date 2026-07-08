package com.navix.notification.template;

import com.navix.common.notification.NotificationChannel;

/**
 * A fully-rendered message for one channel (placeholders substituted). {@code subject} is null for SMS.
 * {@code smsTemplateKey} carries the originating {@code NotificationType} name so the SMS gateway can
 * resolve the per-type DLT Template ID; it is null for non-SMS channels (only {@code SmsSender} reads it).
 */
public record RenderedMessage(NotificationChannel channel, String subject, String body, String smsTemplateKey) {
}
