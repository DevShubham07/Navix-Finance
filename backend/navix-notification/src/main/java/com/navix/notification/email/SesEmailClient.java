package com.navix.notification.email;

import com.navix.common.util.Masking;
import com.navix.notification.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * Real AWS SES email client — active only when {@code navix.email.provider=ses}. Sends over the SES API
 * via the auto-configured {@link SesClient} (same region + default credential chain as S3, so no separate
 * SMTP credentials). The {@code from} address (and its domain) must be a <b>verified SES identity</b>, and
 * the account must be out of the SES sandbox to reach unverified recipients. Returns a failed
 * {@link EmailResult} rather than throwing, so the {@code EmailSender}'s isolation still holds.
 */
@Component
@ConditionalOnProperty(name = "navix.email.provider", havingValue = "ses")
public class SesEmailClient implements EmailClient {

    private static final Logger log = LoggerFactory.getLogger(SesEmailClient.class);

    private final SesClient ses;
    private final EmailProperties props;

    public SesEmailClient(SesClient ses, EmailProperties props) {
        this.ses = ses;
        this.props = props;
    }

    @Override
    public EmailResult send(EmailMessage message) {
        try {
            String subject = message.subject() == null ? "NAVIX Finance" : message.subject();
            String body = message.body() == null ? "" : message.body();
            Body.Builder bodyBuilder = Body.builder().text(utf8(body));
            if (message.html() != null && !message.html().isBlank()) {
                bodyBuilder.html(utf8(message.html()));
            }
            SendEmailRequest.Builder req = SendEmailRequest.builder()
                    .source(props.from())
                    .destination(Destination.builder().toAddresses(message.to()).build())
                    .message(Message.builder()
                            .subject(utf8(subject))
                            .body(bodyBuilder.build())
                            .build());
            // Tag the send with the SES configuration set so bounce/complaint events fire to SNS.
            if (props.configurationSet() != null) {
                req.configurationSetName(props.configurationSet());
            }
            SendEmailResponse resp = ses.sendEmail(req.build());
            return EmailResult.ok(resp.messageId());
        } catch (Exception e) {
            log.warn("EMAIL [ses] send failed to={}: {}", Masking.maskEmail(message.to()), e.getMessage());
            return EmailResult.fail(e.getMessage());
        }
    }

    private static Content utf8(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}
