package com.navix.notification.email;

import com.navix.common.notification.MailPort;
import com.navix.notification.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Notification-module implementation of the {@link MailPort} seam: delegates to the existing
 * {@link EmailClient} + {@link EmailHtmlRenderer} so a module that must not depend on
 * {@code navix-notification} internals (today only {@code navix-loan}'s DSA lead outreach) can
 * still send a real, branded email. Mirrors {@code LoanDirectoryAdapter} — wired by component
 * scan, consumed across the module boundary.
 */
@Component
public class MailPortEmailAdapter implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(MailPortEmailAdapter.class);

    private final EmailClient emailClient;
    private final EmailHtmlRenderer htmlRenderer;
    private final EmailProperties emailProperties;

    public MailPortEmailAdapter(EmailClient emailClient, EmailHtmlRenderer htmlRenderer,
                                EmailProperties emailProperties) {
        this.emailClient = emailClient;
        this.htmlRenderer = htmlRenderer;
        this.emailProperties = emailProperties;
    }

    @Override
    public boolean send(String to, String subject, String textBody) {
        if (!Boolean.TRUE.equals(emailProperties.enabled())) {
            log.info("EMAIL disabled — outbound mail via MailPort not sent");
            return false;
        }
        String html = htmlRenderer.render(subject, textBody);
        EmailResult result = emailClient.send(new EmailMessage(to, subject, textBody, html));
        if (!result.ok()) {
            log.warn("MailPort send failed: {}", result.error());
            return false;
        }
        return true;
    }
}
