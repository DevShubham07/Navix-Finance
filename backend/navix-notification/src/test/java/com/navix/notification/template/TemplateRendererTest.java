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
        // KYC_SUBMITTED only defines IN_APP + EMAIL templates — SMS is undefined → null (channel skipped).
        assertThat(renderer.render(NotificationType.KYC_SUBMITTED, NotificationChannel.SMS, Map.of())).isNull();
    }

    @Test
    void kycSubmittedHasAnEmailTemplate() {
        RenderedMessage m = renderer.render(NotificationType.KYC_SUBMITTED, NotificationChannel.EMAIL,
                Map.of("applicationId", 42L, "name", "Priya"));
        assertThat(m).isNotNull();
        assertThat(m.subject()).contains("42");
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
    void kycApprovedEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.KYC_APPROVED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void kycRejectedEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.KYC_REJECTED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void kycReminderEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.KYC_REMINDER, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "pendingSteps", "PAN, selfie"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void loanDisbursedEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.LOAN_DISBURSED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "netDisbursed", "₹8,820",
                        "totalRepayable", "₹12,700", "dueDate", "30 Jun 2026"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void repaymentRejectedEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.REPAYMENT_REJECTED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "loanId", 2L, "amount", "₹500",
                        "reason", "the reference number didn't match our records"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void paymentDueSoonEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.PAYMENT_DUE_SOON, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "amount", "₹500", "daysToDue", 3,
                        "dueDate", "30 Jun 2026"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void paymentOverdueEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.PAYMENT_OVERDUE, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "amount", "₹500", "daysOverdue", 3));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void loanClosedEmailCarriesTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.LOAN_CLOSED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("#42");
    }

    @Test
    void creditRejectedEmailOpensWithTheSoftenedPhrasing() {
        RenderedMessage m = renderer.render(NotificationType.CREDIT_REJECTED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("After assessing loan application #42").contains("Asha");
    }

    @Test
    void kycSubmittedEmailCarriesTheCustomerNameForTheStaffReader() {
        // KYC_SUBMITTED is staff-only: {name} is the staff reader, {customerName} is the borrower.
        RenderedMessage m = renderer.render(NotificationType.KYC_SUBMITTED, NotificationChannel.EMAIL,
                Map.of("name", "Staff Bob", "applicationId", 42L, "customerName", "Priya Singh"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Hi Staff Bob,").contains("Priya Singh").contains("#42");
    }

    @Test
    void creditApprovedEmailCarriesTheCustomerNameAlongsideTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.CREDIT_APPROVED, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "customerName", "Priya Singh"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("Priya Singh").contains("#42");
    }

    @Test
    void loanAppliedFastTrackEmailCarriesTheCustomerNameAlongsideTheApplicationId() {
        RenderedMessage m = renderer.render(NotificationType.LOAN_APPLIED_FAST_TRACK, NotificationChannel.EMAIL,
                Map.of("name", "Asha", "applicationId", 42L, "customerName", "Priya Singh"));
        assertThat(m).isNotNull();
        assertThat(m.body()).contains("Asha").contains("Priya Singh").contains("#42");
    }

    @Test
    void formatHelpers() {
        assertThat(NotificationFormat.inr(882_000L)).isEqualTo("₹8,820");
        assertThat(NotificationFormat.inr(null)).isEqualTo("—");
        assertThat(NotificationFormat.date(LocalDate.of(2026, 6, 30))).isEqualTo("30 Jun 2026");
        assertThat(NotificationFormat.date(null)).isEqualTo("—");
    }
}
