package com.navix.notification.template;

import com.navix.common.notification.NotificationChannel;
import com.navix.notification.email.EmailAttachment;
import java.util.List;

/**
 * A fully-rendered message for one channel (placeholders substituted). {@code subject} is null for SMS.
 * {@code smsTemplateKey} carries the originating {@code NotificationType} name so the SMS gateway can
 * resolve the per-type DLT Template ID; it is null for non-SMS channels (only {@code SmsSender} reads it).
 * {@code attachments} is EMAIL-only (empty for every other channel) and is populated by the dispatcher
 * from the {@code NotificationContext}, not by {@link TemplateRenderer}.
 */
public record RenderedMessage(NotificationChannel channel, String subject, String body,
                              String smsTemplateKey, List<EmailAttachment> attachments) {

    /** Back-compat constructor for the existing render call sites — no attachments. */
    public RenderedMessage(NotificationChannel channel, String subject, String body, String smsTemplateKey) {
        this(channel, subject, body, smsTemplateKey, List.of());
    }

    public RenderedMessage {
        attachments = attachments == null ? List.of() : attachments;
    }
}
