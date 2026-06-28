package com.navix.notification.email;

/**
 * Engine-internal email port. Default impl is {@link LogEmailClient} (renders + logs, no real send);
 * {@link SmtpEmailClient} sends for real when {@code navix.email.provider=smtp} and {@code spring.mail.*}
 * are configured. Mirrors the {@code NAVIX_SMS_MOCK} philosophy — real delivery flips on via one flag.
 */
public interface EmailClient {

    EmailResult send(EmailMessage message);
}
