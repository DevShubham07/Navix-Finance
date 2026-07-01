package com.navix.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import com.navix.notification.email.LogEmailClient;
import com.navix.notification.suppression.EmailSuppressionService;
import com.navix.notification.template.RenderedMessage;
import org.junit.jupiter.api.Test;

/** Email transport gating: the global enabled flag, the per-recipient address, suppression, and the log client. */
class EmailSenderTest {

    private static final RenderedMessage MSG =
            new RenderedMessage(NotificationChannel.EMAIL, "Subject", "Body");

    private final EmailSuppressionService allowAll = noSuppression();

    private static ContactInfo withEmail(String email) {
        return new ContactInfo(RecipientType.BORROWER, 1L, "Asha", email, null, "BORROWER");
    }

    private static EmailProperties props(boolean enabled) {
        return new EmailProperties("log", enabled, null, null, null);
    }

    /** A suppression service that suppresses nothing (the default for most cases). */
    private static EmailSuppressionService noSuppression() {
        EmailSuppressionService svc = mock(EmailSuppressionService.class);
        when(svc.isSuppressed(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        return svc;
    }

    @Test
    void skipsWhenEmailDisabledGlobally() {
        EmailSender sender = new EmailSender(new LogEmailClient(), props(false), allowAll);

        DeliveryOutcome outcome = sender.send(MSG, withEmail("asha@x.test"));

        assertThat(outcome.status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(outcome.error()).isEqualTo("EMAIL_DISABLED");
    }

    @Test
    void skipsWhenRecipientHasNoEmail() {
        EmailSender sender = new EmailSender(new LogEmailClient(), props(true), allowAll);

        DeliveryOutcome outcome = sender.send(MSG, withEmail(null));

        assertThat(outcome.status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(outcome.error()).isEqualTo("NO_EMAIL");
    }

    @Test
    void skipsAndDoesNotSendWhenSuppressed() {
        EmailClient client = mock(EmailClient.class);
        EmailSuppressionService suppressed = mock(EmailSuppressionService.class);
        when(suppressed.isSuppressed("asha@x.test")).thenReturn(true);
        EmailSender sender = new EmailSender(client, props(true), suppressed);

        DeliveryOutcome outcome = sender.send(MSG, withEmail("asha@x.test"));

        assertThat(outcome.status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(outcome.error()).isEqualTo("SUPPRESSED");
        verify(client, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sendsViaClientWhenEnabledAndAddressed() {
        EmailSender sender = new EmailSender(new LogEmailClient(), props(true), allowAll);

        DeliveryOutcome outcome = sender.send(MSG, withEmail("asha@x.test"));

        assertThat(outcome.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(outcome.providerRef()).startsWith("log-");
    }

    @Test
    void mapsClientFailureToFailedOutcome() {
        EmailClient failing = message -> EmailResult.fail("smtp down");
        EmailSender sender = new EmailSender(failing, props(true), allowAll);

        DeliveryOutcome outcome = sender.send(MSG, withEmail("asha@x.test"));

        assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("smtp down");
    }

    @Test
    void logClientReturnsOkWithMockReference() {
        EmailResult result = new LogEmailClient().send(new EmailMessage("asha@x.test", "Hi", "Body"));

        assertThat(result.ok()).isTrue();
        assertThat(result.providerRef()).startsWith("log-");
    }
}
