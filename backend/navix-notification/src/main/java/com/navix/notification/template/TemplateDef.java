package com.navix.notification.template;

/**
 * A per-channel template before placeholder substitution. {@code subject} is the in-app title or the
 * email subject; it is {@code null} for SMS (body only). {@code body} carries {@code {placeholder}}
 * tokens resolved against the notification model by {@link TemplateRenderer}.
 */
public record TemplateDef(String subject, String body) {
}
