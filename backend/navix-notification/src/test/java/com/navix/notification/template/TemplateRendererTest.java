package com.navix.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.notification.NotificationChannel;
import com.navix.notification.catalog.NotificationType;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Placeholder substitution + the money/date format helpers the templates rely on. */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer(new NotificationTemplates());

    @Test
    void substituteReplacesKnownPlaceholders() {
        String out = TemplateRenderer.substitute(
                "Hi {name}, application #{applicationId}",
                Map.of("name", "Asha", "applicationId", 42L));
        assertThat(out).isEqualTo("Hi Asha, application #42");
    }

    @Test
    void substituteRendersUnknownKeyAsDash() {
        // {dueDate} is absent from the model — it must render as the em-dash, never a raw token.
        String out = TemplateRenderer.substitute("Owe {amount} by {dueDate}", Map.of("amount", "₹1,000"));
        assertThat(out).isEqualTo("Owe ₹1,000 by —");
    }

    @Test
    void substituteHandlesNullValueAsDash() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", null);
        assertThat(TemplateRenderer.substitute("Hi {name}", model)).isEqualTo("Hi —");
    }

    @Test
    void substituteNullTemplateIsNull() {
        assertThat(TemplateRenderer.substitute(null, Map.of())).isNull();
    }

    @Test
    void renderReturnsNullWhenNoTemplateForChannel() {
        // KYC_SUBMITTED only defines an IN_APP template — SMS is undefined → null (channel skipped).
        assertThat(renderer.render(NotificationType.KYC_SUBMITTED, NotificationChannel.SMS, Map.of())).isNull();
    }

    @Test
    void renderSubstitutesIntoSmsBodyWithNoSubject() {
        RenderedMessage m = renderer.render(NotificationType.LOAN_DISBURSED, NotificationChannel.SMS,
                Map.of("netDisbursed", "₹8,820", "totalRepayable", "₹12,700", "dueDate", "30 Jun 2026"));
        assertThat(m).isNotNull();
        assertThat(m.subject()).isNull(); // SMS = body only
        // SMS renders "Rs." not "₹" (GSM-7 / cost), and carries the type name as the DLT key.
        assertThat(m.body()).contains("Rs. 8,820").contains("Rs. 12,700").contains("30 Jun 2026");
        assertThat(m.body()).doesNotContain("₹");
        assertThat(m.smsTemplateKey()).isEqualTo("LOAN_DISBURSED");
    }

    @Test
    void renderKeepsRupeeGlyphForEmail() {
        // The ₹→Rs. swap is SMS-only — EMAIL/IN_APP keep the rupee glyph and carry no SMS template key.
        RenderedMessage m = renderer.render(NotificationType.LOAN_DISBURSED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "netDisbursed", "₹8,820", "totalRepayable", "₹12,700",
                        "dueDate", "30 Jun 2026"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("₹8,820").contains("₹12,700");
        assertThat(m.smsTemplateKey()).isNull();
    }

    @Test
    void renderSubstitutesIntoEmailSubjectAndBody() {
        RenderedMessage m = renderer.render(NotificationType.KYC_APPROVED, NotificationChannel.EMAIL,
                Map.of("name", "Asha"));
        assertThat(m).isNotNull();
        assertThat(m.subject()).isEqualTo("Your DhanBoost KYC is approved — instant loan up to ₹10,00,000");
        assertThat(m.body()).contains("Hi Asha,");
    }

    @Test
    void formatHelpers() {
        assertThat(NotificationFormat.inr(882_000L)).isEqualTo("₹8,820");
        assertThat(NotificationFormat.inr(null)).isEqualTo("—");
        assertThat(NotificationFormat.date(LocalDate.of(2026, 6, 30))).isEqualTo("30 Jun 2026");
        assertThat(NotificationFormat.date(null)).isEqualTo("—");
    }
}
