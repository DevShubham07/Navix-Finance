package com.navix.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.template.NotificationTemplates;
import com.navix.notification.template.RenderedMessage;
import com.navix.notification.template.TemplateRenderer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Template name + ordered {{n}} values survive render → send; blanks become "—"; failures isolate. */
class WhatsAppSenderTest {

    private final WhatsAppGateway gateway = mock(WhatsAppGateway.class);
    private final WhatsAppSender sender = new WhatsAppSender(gateway);
    private final TemplateRenderer renderer = new TemplateRenderer(new NotificationTemplates());
    private final ContactInfo borrower =
            new ContactInfo(RecipientType.BORROWER, 7L, "Asha", null, "9812345678", "BORROWER");

    @Test
    void sendsTemplateWithOrderedParams() {
        Map<String, Object> model = new HashMap<>(Map.of("name", "Asha", "netDisbursed", "₹8,820",
                "totalRepayable", "₹12,700", "dueDate", "30 Jun 2026"));
        RenderedMessage msg = renderer.render(NotificationType.LOAN_DISBURSED, NotificationChannel.WHATSAPP, model);
        when(gateway.sendTemplate(any(), any(), any())).thenReturn("wamid.1");

        DeliveryOutcome out = sender.send(msg, borrower);

        assertThat(out.status()).isEqualTo(DeliveryStatus.SENT);
        verify(gateway).sendTemplate("919812345678", "db_loan_disbursed",
                List.of("Asha", "₹8,820", "₹12,700", "30 Jun 2026"));
    }

    @Test
    void blankValueBecomesDash_andGatewayFailureIsIsolated() {
        Map<String, Object> model = new HashMap<>(Map.of("name", "", "applicationId", 42L));
        RenderedMessage msg = renderer.render(NotificationType.KYC_APPROVED, NotificationChannel.WHATSAPP, model);
        when(gateway.sendTemplate(any(), any(), any())).thenThrow(new RuntimeException("not approved"));

        assertThat(sender.send(msg, borrower).status()).isEqualTo(DeliveryStatus.FAILED);
        verify(gateway).sendTemplate("919812345678", "db_kyc_approved", List.of("—", "42"));
    }

    @Test
    void everyWhatsAppTypeHasATemplate() {
        NotificationTemplates templates = new NotificationTemplates();
        for (NotificationType t : NotificationType.values()) {
            if (t.channels().contains(NotificationChannel.WHATSAPP)) {
                assertThat(templates.get(t, NotificationChannel.WHATSAPP)).as(t.name()).isNotNull();
            }
        }
    }
}
