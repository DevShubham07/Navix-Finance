package com.navix.notification.email;

/**
 * Engine-internal email port. Default impl is {@link LogEmailClient} (renders + logs, no real send);
 * {@link SmtpEmailClient} sends for real when {@code navix.email.provider=smtp} and {@code spring.mail.*}
 * are configured; {@link SesEmailClient} sends over the AWS SES v2 API when {@code navix.email.provider=ses}
 * (reusing the same region + credential chain as S3); {@link ResendEmailClient} sends over the Resend HTTP
 * API when {@code navix.email.provider=resend} (an interim provider while SES is sandbox-blocked). Mirrors
 * the {@code NAVIX_SMS_MOCK} philosophy — real delivery flips on via one flag.
 */
public interface EmailClient {

    EmailResult send(EmailMessage message);
}
