package com.navix.notification.email;

import com.navix.common.util.Masking;
import com.navix.notification.config.EmailProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Real email client over the <a href="https://resend.com">Resend</a> HTTP API — active only when
 * {@code navix.email.provider=resend}. A drop-in alternative to {@link SesEmailClient} while SES is
 * blocked in the sandbox: same {@link EmailClient} port, so switching providers is a one-line config
 * flip. The {@code from} domain must be a <b>verified Resend domain</b> (or {@code onboarding@resend.dev}
 * for a quick self-test). Returns a failed {@link EmailResult} rather than throwing, so the
 * {@code EmailSender}'s isolation still holds.
 *
 * <p>Note: bounce/complaint feedback from Resend arrives via Resend <b>webhooks</b>, not the SES
 * SNS/SQS path — so the {@code email_suppression} list is not auto-fed while on this provider.
 */
@Component
@ConditionalOnProperty(name = "navix.email.provider", havingValue = "resend")
public class ResendEmailClient implements EmailClient {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailClient.class);
    private static final String ENDPOINT = "https://api.resend.com/emails";

    private final EmailProperties props;
    private final RestClient http;

    public ResendEmailClient(EmailProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(ENDPOINT).build();
    }

    @Override
    public EmailResult send(EmailMessage message) {
        if (props.resendApiKey() == null) {
            return EmailResult.fail("RESEND_API_KEY not configured");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("from", props.from());
            body.put("to", List.of(message.to()));
            body.put("subject", message.subject() == null ? "DhanBoost" : message.subject());
            body.put("text", message.body() == null ? "" : message.body());
            if (message.html() != null && !message.html().isBlank()) {
                body.put("html", message.html());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.post()
                    .header("Authorization", "Bearer " + props.resendApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Object id = resp == null ? null : resp.get("id");
            return EmailResult.ok(id == null ? "resend" : id.toString());
        } catch (Exception e) {
            log.warn("EMAIL [resend] send failed to={}: {}", Masking.maskEmail(message.to()), e.getMessage());
            return EmailResult.fail(e.getMessage());
        }
    }
}
