package com.navix.notification.listener;

import com.navix.common.esign.EsignInviteOutcomeSink;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.common.notification.event.EsignInviteEvent;
import com.navix.notification.channel.DeliveryOutcome;
import com.navix.notification.channel.EmailSender;
import com.navix.notification.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails an external signer their eSign link. Bypasses the notification log (the recipient is not a
 * user), so the outcome goes back to the signer row through {@link EsignInviteOutcomeSink}.
 * {@code fallbackExecution} because "Resend" may run outside a transaction.
 */
@Component
public class EsignInviteListener {

    private static final Logger log = LoggerFactory.getLogger(EsignInviteListener.class);

    private final EmailSender email;
    private final EsignInviteOutcomeSink sink;

    public EsignInviteListener(EmailSender email, EsignInviteOutcomeSink sink) {
        this.email = email;
        this.sink = sink;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInvite(EsignInviteEvent e) {
        String body = "Hello " + e.signerName() + ",\n\n"
                + "You have been asked to e-sign \"" + e.documentTitle() + "\" using Aadhaar OTP.\n\n"
                + "Sign here: " + e.signUrl() + "\n\n"
                + "Sign with the Aadhaar that carries your name exactly as written above. "
                + "The link expires in about 7 days.";
        String status;
        try {
            DeliveryOutcome out = email.send(
                    new RenderedMessage(NotificationChannel.EMAIL, "Please e-sign: " + e.documentTitle(),
                            body, null),
                    new ContactInfo(RecipientType.STAFF, null, e.signerName(), e.email(), null, "SIGNER"));
            status = out.status().name().equals("SENT") ? "SENT"
                    : out.status().name().equals("SKIPPED") ? "SKIPPED" : "FAILED";
        } catch (RuntimeException ex) {
            status = "FAILED";
        }
        try {
            sink.recordInviteOutcome(e.signerRowId(), status);
        } catch (RuntimeException ex) {
            log.warn("Could not record eSign invite outcome for signer row {}: {}", e.signerRowId(), ex.toString());
        }
    }
}
