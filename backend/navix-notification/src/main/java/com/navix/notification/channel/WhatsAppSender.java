package com.navix.notification.channel;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.util.Masking;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.notification.template.RenderedMessage;
import com.navix.notification.template.TemplateRenderer;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * WhatsApp transport over the {@link WhatsAppGateway} port (SmartChat). The rendered message carries
 * the approved template name in {@code subject} and its ordered parameter values in {@code body},
 * joined by {@link TemplateRenderer#WHATSAPP_PARAM_SEPARATOR}. Same mobile as SMS; failures isolated.
 */
@Component
public class WhatsAppSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSender.class);

    private final WhatsAppGateway gateway;

    public WhatsAppSender(WhatsAppGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WHATSAPP;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message, ContactInfo recipient) {
        String mobile = recipient.mobile();
        if (mobile == null || mobile.isBlank()) {
            return DeliveryOutcome.skipped("NO_MOBILE");
        }
        // Meta rejects a send with an empty parameter; "—" is the renderer's own blank.
        List<String> params = message.body().isEmpty()
                ? List.of()
                : Arrays.stream(message.body().split(TemplateRenderer.WHATSAPP_PARAM_SEPARATOR, -1))
                        .map(p -> p.isBlank() ? "—" : p)
                        .toList();
        try {
            return DeliveryOutcome.sent(gateway.sendTemplate("91" + mobile, message.subject(), params));
        } catch (RuntimeException e) {
            log.warn("WhatsApp notification failed to {}: {}", Masking.maskPhone(mobile), e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        }
    }
}
