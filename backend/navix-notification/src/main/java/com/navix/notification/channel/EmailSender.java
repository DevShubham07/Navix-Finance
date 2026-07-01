package com.navix.notification.channel;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailHtmlRenderer;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import com.navix.notification.suppression.EmailSuppressionService;
import com.navix.notification.template.RenderedMessage;
import org.springframework.stereotype.Component;

/**
 * Email transport over the {@link EmailClient} port ({@code LogEmailClient} by default, {@code
 * SmtpEmailClient}/{@code SesEmailClient}/{@code ResendEmailClient} when configured). Honours the
 * global enabled flag, address-gates a missing email, skips addresses on the bounce/complaint
 * suppression list, and wraps the body in a branded HTML layout ({@link EmailHtmlRenderer}); never throws.
 */
@Component
public class EmailSender implements ChannelSender {

    private final EmailClient client;
    private final EmailProperties props;
    private final EmailSuppressionService suppressionService;
    private final EmailHtmlRenderer htmlRenderer;

    public EmailSender(EmailClient client, EmailProperties props,
                       EmailSuppressionService suppressionService, EmailHtmlRenderer htmlRenderer) {
        this.client = client;
        this.props = props;
        this.suppressionService = suppressionService;
        this.htmlRenderer = htmlRenderer;
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
        if (suppressionService.isSuppressed(to)) {
            return DeliveryOutcome.skipped("SUPPRESSED");
        }
        try {
            String html = htmlRenderer.render(message.subject(), message.body());
            EmailResult result = client.send(new EmailMessage(to, message.subject(), message.body(), html));
            return result.ok()
                    ? DeliveryOutcome.sent(result.providerRef())
                    : DeliveryOutcome.failed(result.error());
        } catch (RuntimeException e) {
            return DeliveryOutcome.failed(e.getMessage());
        }
    }
}
