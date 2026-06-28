package com.navix.notification.channel;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import com.navix.notification.template.RenderedMessage;
import org.springframework.stereotype.Component;

/**
 * Email transport over the {@link EmailClient} port ({@code LogEmailClient} by default, {@code
 * SmtpEmailClient} when {@code navix.email.provider=smtp}). Honours the global enabled flag and
 * address-gates a missing email; never throws.
 */
@Component
public class EmailSender implements ChannelSender {

    private final EmailClient client;
    private final EmailProperties props;

    public EmailSender(EmailClient client, EmailProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message, ContactInfo recipient) {
        if (!props.enabled()) {
            return DeliveryOutcome.skipped("EMAIL_DISABLED");
        }
        String to = recipient.email();
        if (to == null || to.isBlank()) {
            return DeliveryOutcome.skipped("NO_EMAIL");
        }
        try {
            EmailResult result = client.send(new EmailMessage(to, message.subject(), message.body()));
            return result.ok()
                    ? DeliveryOutcome.sent(result.providerRef())
                    : DeliveryOutcome.failed(result.error());
        } catch (RuntimeException e) {
            return DeliveryOutcome.failed(e.getMessage());
        }
    }
}
