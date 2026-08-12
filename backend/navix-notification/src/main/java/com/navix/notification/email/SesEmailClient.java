package com.navix.notification.email;

import com.navix.common.util.Masking;
import com.navix.notification.config.EmailProperties;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.RawMessage;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse;

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
        boolean hasAttachments = message.attachments() != null && !message.attachments().isEmpty();
        try {
            return hasAttachments ? sendRaw(message) : sendSimple(message);
        } catch (Exception e) {
            log.warn("EMAIL [ses] send failed to={}: {}", Masking.maskEmail(message.to()), e.getMessage());
            return EmailResult.fail(e.getMessage());
        }
    }

    private EmailResult sendSimple(EmailMessage message) {
        String subject = message.subject() == null ? "DhanBoost" : message.subject();
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
    }

    /**
     * SES's simple {@code SendEmail} API has no attachment support — attachments require
     * {@code SendRawEmail} with a hand-built MIME message (Jakarta Mail, no live SMTP transport
     * involved — {@link Session#getInstance} with empty properties just gives us a MIME builder).
     */
    private EmailResult sendRaw(EmailMessage message) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage mime = new MimeMessage(session);
        mime.setFrom(new InternetAddress(props.from()));
        mime.setRecipients(RecipientType.TO, InternetAddress.parse(message.to()));
        mime.setSubject(message.subject() == null ? "DhanBoost" : message.subject(), "UTF-8");

        MimeMultipart mixed = new MimeMultipart("mixed");

        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(message.body() == null ? "" : message.body(), "UTF-8");
        alt.addBodyPart(text);
        if (message.html() != null && !message.html().isBlank()) {
            MimeBodyPart html = new MimeBodyPart();
            html.setContent(message.html(), "text/html; charset=UTF-8");
            alt.addBodyPart(html);
        }
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        mixed.addBodyPart(altPart);

        for (EmailAttachment a : message.attachments()) {
            MimeBodyPart part = new MimeBodyPart();
            part.setFileName(a.filename());
            part.setContent(a.content(), a.contentType() != null ? a.contentType() : "application/octet-stream");
            mixed.addBodyPart(part);
        }
        mime.setContent(mixed);
        mime.saveChanges();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mime.writeTo(out);

        SendRawEmailRequest.Builder req = SendRawEmailRequest.builder()
                .rawMessage(RawMessage.builder().data(SdkBytes.fromByteArray(out.toByteArray())).build());
        if (props.configurationSet() != null) {
            req.configurationSetName(props.configurationSet());
        }
        SendRawEmailResponse resp = ses.sendRawEmail(req.build());
        return EmailResult.ok(resp.messageId());
    }

    private static Content utf8(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}
