package com.navix.contact;

import com.navix.common.exception.BusinessException;
import com.navix.common.util.Masking;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Delivers a marketing "Contact us" submission straight to the NAVIX support inbox
 * ({@code navix.contact.recipient}, default {@code navixfinance@gmail.com}) via the shared
 * {@link EmailClient} — the same engine that powers every other outbound email.
 *
 * <p>Sent directly (like {@code PasswordResetService}), deliberately <b>not</b> through the
 * notification dispatcher: this message is addressed to a fixed operational mailbox, not to a
 * borrower/staff inbox. Delivery is gated on {@code navix.email.enabled}; with the default {@code log}
 * provider the message is only logged (no real send). The visitor's own email is put in the body so the
 * team can simply hit reply.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final EmailClient emailClient;
    private final EmailProperties emailProperties;
    private final String recipient;

    public ContactService(EmailClient emailClient,
                          EmailProperties emailProperties,
                          @Value("${navix.contact.recipient:navixfinance@gmail.com}") String recipient) {
        this.emailClient = emailClient;
        this.emailProperties = emailProperties;
        this.recipient = recipient;
    }

    /** Email one contact-form submission to the support inbox; throws if a real send fails. */
    public void submit(ContactDtos.ContactRequest req) {
        String name = safe(req.name());
        String email = safe(req.email());
        String phone = blankToDash(req.phone());
        String topic = req.topic() == null || req.topic().isBlank() ? "General enquiry" : req.topic().trim();
        String message = safe(req.message());

        String subject = "[Contact · " + topic + "] " + name;
        String body = "New enquiry from the NAVIX website contact form.\n\n"
                + "Name:    " + name + "\n"
                + "Email:   " + email + "\n"
                + "Phone:   " + phone + "\n"
                + "Topic:   " + topic + "\n\n"
                + "Message:\n" + message + "\n\n"
                + "— Reply directly to " + email + " to respond to this enquiry.";

        if (!Boolean.TRUE.equals(emailProperties.enabled())) {
            log.info("EMAIL disabled — contact enquiry from {} not sent", Masking.maskEmail(email));
            return;
        }
        // Dev convenience: with the log-only provider no real mail goes out, so surface it in the log.
        if ("log".equalsIgnoreCase(emailProperties.provider())) {
            log.info("DEV contact enquiry to {} from {} topic={}",
                    Masking.maskEmail(recipient), Masking.maskEmail(email), topic);
        }
        EmailResult result = emailClient.send(new EmailMessage(recipient, subject, body, null));
        if (!result.ok()) {
            log.warn("contact enquiry email to {} failed: {}", Masking.maskEmail(recipient), result.error());
            throw new BusinessException("CONTACT_SEND_FAILED",
                    "We couldn't send your message right now. Please try again, or email us directly.");
        }
        log.info("contact enquiry delivered to {} topic={}", Masking.maskEmail(recipient), topic);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "—" : s.trim();
    }
}
