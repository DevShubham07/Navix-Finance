package com.navix.notification.email;

import com.navix.notification.config.EmailProperties;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real SMTP email client — active only when {@code navix.email.provider=smtp}. Wraps the Boot-provided
 * {@link JavaMailSender} (auto-configured when {@code spring.mail.host} is set). Returns a failed
 * {@link EmailResult} rather than throwing, so the {@code EmailSender}'s isolation still holds.
 */
@Component
@ConditionalOnProperty(name = "navix.email.provider", havingValue = "smtp")
public class SmtpEmailClient implements EmailClient {

    private final JavaMailSender mailSender;
    private final EmailProperties props;

    public SmtpEmailClient(JavaMailSender mailSender, EmailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public EmailResult send(EmailMessage message) {
        try {
            boolean hasHtml = message.html() != null && !message.html().isBlank();
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, hasHtml, "UTF-8");
            helper.setFrom(props.from());
            helper.setTo(message.to());
            helper.setSubject(message.subject() == null ? "DhanBoost" : message.subject());
            String text = message.body() == null ? "" : message.body();
            if (hasHtml) {
                helper.setText(text, message.html());
            } else {
                helper.setText(text, false);
            }
            mailSender.send(mime);
            return EmailResult.ok("smtp");
        } catch (Exception e) {
            return EmailResult.fail(e.getMessage());
        }
    }
}
