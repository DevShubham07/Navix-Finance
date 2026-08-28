package com.navix.notification.email;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.navix.notification.config.EmailProperties;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The signed sanction letter is delivered as an email attachment. This client used to log a warning
 * and drop attachments, so on this provider the borrower received the mail with no letter in it —
 * these assertions are what stops that regressing.
 */
class ResendEmailClientTest {

    private final ResendEmailClient client = new ResendEmailClient(props());

    @Test
    void attachmentsAreSentAsBase64Content() {
        byte[] pdf = "%PDF-1.4 sanction letter".getBytes(UTF_8);
        EmailMessage message = new EmailMessage("borrower@example.com", "Your sanction letter",
                "Attached.", null,
                List.of(new EmailAttachment("sanction-letter-signed.pdf", "application/pdf", pdf)));

        Map<String, Object> body = client.buildBody(message);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> attachments = (List<Map<String, String>>) body.get("attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).get("filename")).isEqualTo("sanction-letter-signed.pdf");
        // Round-trips to the exact bytes — a truncated or re-encoded PDF is an unopenable one.
        assertThat(Base64.getDecoder().decode(attachments.get(0).get("content"))).isEqualTo(pdf);
    }

    @Test
    void anOrdinaryEmailCarriesNoAttachmentsKey() {
        Map<String, Object> body = client.buildBody(
                new EmailMessage("borrower@example.com", "Hello", "Text", null));

        assertThat(body).doesNotContainKey("attachments");
        assertThat(body.get("to")).isEqualTo(List.of("borrower@example.com"));
        assertThat(body.get("subject")).isEqualTo("Hello");
    }

    private static EmailProperties props() {
        return new EmailProperties("resend", true, "noreply@dhanboost.com", null, "re_test_key");
    }
}
