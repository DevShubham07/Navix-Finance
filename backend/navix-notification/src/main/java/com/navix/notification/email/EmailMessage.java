package com.navix.notification.email;

import java.util.List;

/**
 * A ready-to-send email: recipient address, rendered subject, the plain-text body, a branded
 * {@code html} alternative (built by {@code EmailHtmlRenderer}, may be {@code null} — then clients
 * send text only), and optional {@code attachments} (empty by default).
 */
public record EmailMessage(String to, String subject, String body, String html,
                           List<EmailAttachment> attachments) {

    /** Canonical 4-arg constructor kept for the ~existing call sites — no attachments. */
    public EmailMessage(String to, String subject, String body, String html) {
        this(to, subject, body, html, List.of());
    }

    public EmailMessage {
        attachments = attachments == null ? List.of() : attachments;
    }
}
