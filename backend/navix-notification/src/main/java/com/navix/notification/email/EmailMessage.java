package com.navix.notification.email;

/**
 * A ready-to-send email: recipient address, rendered subject, the plain-text body, and a branded
 * {@code html} alternative (built by {@code EmailHtmlRenderer}). {@code html} may be {@code null}
 * (then clients send text only).
 */
public record EmailMessage(String to, String subject, String body, String html) {
}
