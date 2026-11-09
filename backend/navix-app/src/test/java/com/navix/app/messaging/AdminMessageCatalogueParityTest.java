package com.navix.app.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.notification.NotificationChannel;
import com.navix.loan.service.MessageCatalogue;
import com.navix.loan.service.MessageCatalogue.Template;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.template.NotificationTemplates;
import com.navix.notification.template.TemplateDef;
import com.navix.notification.template.TemplateRenderer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * navix-loan owns the admin catalogue but cannot see navix-notification, so the two existing templates
 * (payment overdue / due soon) are duplicated there. This test is what keeps the copies identical to the
 * registered SMS text, WhatsApp name and parameter order, and the DLT key present in the app config.
 */
class AdminMessageCatalogueParityTest {

    private final NotificationTemplates templates = new NotificationTemplates();

    private void assertParity(String key, NotificationType type) {
        Template t = MessageCatalogue.find(key);
        TemplateDef sms = templates.get(type, NotificationChannel.SMS);
        TemplateDef wa = templates.get(type, NotificationChannel.WHATSAPP);

        assertThat(t.smsBody()).isEqualTo(sms.body());
        assertThat(t.smsDltKey()).isEqualTo(type.name());
        assertThat(t.provider()).isEqualTo(wa.subject());
        List<String> registryKeys = Arrays.stream(wa.body().split(TemplateRenderer.WHATSAPP_PARAM_SEPARATOR))
                .map(s -> s.substring(1, s.length() - 1)).toList();
        assertThat(t.params()).isEqualTo(registryKeys);
        assertThat(t.channels()).containsExactly("SMS", "WHATSAPP");
    }

    @Test
    void paymentOverdueMatchesTheRegistry() {
        assertParity("payment_overdue", NotificationType.PAYMENT_OVERDUE);
    }

    @Test
    void paymentDueSoonMatchesTheRegistry() {
        assertParity("payment_due_soon", NotificationType.PAYMENT_DUE_SOON);
    }

    @Test
    void renderedSmsIsCharacterForCharacterTheEngineOutputWithRsNotRupeeSign() {
        TemplateRenderer renderer = new TemplateRenderer(templates);
        String engine = renderer.render(NotificationType.PAYMENT_OVERDUE, NotificationChannel.SMS,
                Map.of("name", "Asha Rao", "amount", "₹12,700", "loanId", 50, "daysOverdue", 3)).body();
        Template t = MessageCatalogue.find("payment_overdue");
        String admin = MessageCatalogue.renderSms(t, Map.of("name", "Asha Rao", "amount", "Rs. 12,700",
                "loanId", "50", "daysOverdue", "3"));
        assertThat(admin).isEqualTo(engine).doesNotContain("₹");
    }

    @Test
    void dltKeysExistInTheAppConfig() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        // The main file, not the classpath one: src/test/resources has its own application.yml.
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).containsKeys("navix.sms.dlt-template-ids.PAYMENT_OVERDUE",
                "navix.sms.dlt-template-ids.PAYMENT_DUE_SOON");
    }

    @Test
    void whatsAppProviderNamesMatchTheSpec() {
        assertThat(MessageCatalogue.ALL).hasSize(23);
        assertThat(MessageCatalogue.find("repay_discount").reviewProvider())
                .isEqualTo("db_repay_discount_review_v1_164");
    }
}
