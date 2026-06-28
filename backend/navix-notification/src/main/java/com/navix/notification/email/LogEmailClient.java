package com.navix.notification.email;

import com.navix.common.util.Masking;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default email client (pre-go-live, no SMTP host yet): renders and <b>logs</b> the email with a
 * masked recipient, recording a delivery as {@code SENT} with a mock reference. Active unless
 * {@code navix.email.provider=smtp}.
 */
@Component
@ConditionalOnProperty(name = "navix.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailClient implements EmailClient {

    private static final Logger log = LoggerFactory.getLogger(LogEmailClient.class);

    @Override
    public EmailResult send(EmailMessage message) {
        log.info("EMAIL [log] to={} subject=\"{}\"", Masking.maskEmail(message.to()), message.subject());
        return EmailResult.ok("log-" + UUID.randomUUID());
    }
}
