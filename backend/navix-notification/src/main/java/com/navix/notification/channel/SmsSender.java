package com.navix.notification.channel;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.sms.SmsGateway;
import com.navix.common.util.Masking;
import com.navix.notification.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SMS transport over the {@link SmsGateway} port (the UltronSMS client). The gateway honours SMS mock
 * mode itself (a mock reference, no real send), so this sender just dials {@code 91 + mobile} and
 * isolates any failure. Staff carry no mobile, so they are skipped upstream by address-gating.
 */
@Component
public class SmsSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

    private final SmsGateway gateway;

    public SmsSender(SmsGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message, ContactInfo recipient) {
        String mobile = recipient.mobile();
        if (mobile == null || mobile.isBlank()) {
            return DeliveryOutcome.skipped("NO_MOBILE");
        }
        try {
            String ref = gateway.send("91" + mobile, message.body(), message.smsTemplateKey());
            return DeliveryOutcome.sent(ref);
        } catch (RuntimeException e) {
            log.warn("SMS notification failed to {}: {}", Masking.maskPhone(mobile), e.getMessage());
            return DeliveryOutcome.failed(e.getMessage());
        }
    }
}
