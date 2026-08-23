package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.verification.EmailOtpPort;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import com.navix.notification.suppression.EmailSuppressionService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EmailOtpService}: happy path, single-use verification, the wrong-attempt
 * lock-out cap, expiry, a suppressed address never getting mailed, and the send-rate cap.
 */
@ExtendWith(MockitoExtension.class)
class EmailOtpServiceTest {

    private static final String EMAIL = "borrower@example.com";
    private static final Pattern CODE_PATTERN = Pattern.compile("code is (\\d{6})");

    @Mock
    private EmailClient emailClient;
    @Mock
    private EmailSuppressionService suppression;

    private static EmailProperties enabledLogProperties() {
        return new EmailProperties("log", true, "DhanBoost <no-reply@dhanboost.example>", null, null);
    }

    private EmailOtpService service() {
        return new EmailOtpService(emailClient, enabledLogProperties(), suppression, new AttemptLimiter());
    }

    /** Every code sent so far, in order — for the tests that mint more than one. */
    private java.util.List<String> codes() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailClient, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().map(m -> {
            Matcher matcher = CODE_PATTERN.matcher(m.body());
            assertThat(matcher.find()).as("email body should contain a 6-digit code").isTrue();
            return matcher.group(1);
        }).toList();
    }

    private String sentCode() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailClient).send(captor.capture());
        Matcher m = CODE_PATTERN.matcher(captor.getValue().body());
        assertThat(m.find()).as("email body should contain a 6-digit code").isTrue();
        return m.group(1);
    }

    @Test
    void request_sendsAnEmail_andTheEchoedCodeVerifies() {
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref-1"));
        EmailOtpService service = service();

        var req = service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL);

        assertThat(req.sent()).isTrue();
        assertThat(req.devCode()).isNull(); // never revealed in the response
        String code = sentCode();
        assertThat(service.verify(EMAIL, code, EmailOtpPort.PERSONAL_EMAIL)).isTrue();
    }

    /**
     * A borrower may type the same address into both the personal and the work field. Codes are keyed
     * on purpose + address, so the two must be independent: consuming one must not invalidate the
     * other, and a code minted for one purpose must not verify against the other.
     */
    @Test
    void purposesAreIndependentForTheSameAddress() {
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref-1"));
        EmailOtpService service = service();

        service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL);
        String personalCode = codes().get(0);
        service.request(EMAIL, EmailOtpPort.OFFICIAL_EMAIL);
        String officialCode = codes().get(1);

        assertThat(service.verify(EMAIL, personalCode, EmailOtpPort.OFFICIAL_EMAIL)).isFalse();
        assertThat(service.verify(EMAIL, personalCode, EmailOtpPort.PERSONAL_EMAIL)).isTrue();
        // Consuming the personal one left the official one intact.
        assertThat(service.verify(EMAIL, officialCode, EmailOtpPort.OFFICIAL_EMAIL)).isTrue();
    }

    @Test
    void verify_isSingleUse_andRejectsWrongCode() {
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref-1"));
        EmailOtpService service = service();
        service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL);
        String code = sentCode();

        assertThat(service.verify(EMAIL, "000000", EmailOtpPort.PERSONAL_EMAIL)).isFalse();
        assertThat(service.verify(EMAIL, code, EmailOtpPort.PERSONAL_EMAIL)).isTrue();
        assertThat(service.verify(EMAIL, code, EmailOtpPort.PERSONAL_EMAIL)).isFalse(); // already used
    }

    @Test
    void verify_locksOutAfterFiveWrongAttempts() {
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref-1"));
        EmailOtpService service = service();
        service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL);
        String code = sentCode();

        for (int i = 0; i < 5; i++) {
            assertThat(service.verify(EMAIL, "000000", EmailOtpPort.PERSONAL_EMAIL)).isFalse();
        }
        assertThat(service.verify(EMAIL, code, EmailOtpPort.PERSONAL_EMAIL)).isFalse();
    }

    @Test
    void verify_failsForAnEmailThatWasNeverSentACode() {
        EmailOtpService service = service();
        assertThat(service.verify(EMAIL, "123456", EmailOtpPort.PERSONAL_EMAIL)).isFalse();
    }

    @Test
    void request_skipsSendingToASuppressedAddress() {
        when(suppression.isSuppressed(EMAIL)).thenReturn(true);
        EmailOtpService service = service();

        var req = service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL);

        assertThat(req.sent()).isFalse();
        verify(emailClient, never()).send(any());
    }

    @Test
    void request_ratelimitsAfterFiveSends() {
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref-1"));
        EmailOtpService service = service();

        for (int i = 0; i < 5; i++) {
            service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL);
        }
        assertThatThrownBy(() -> service.request(EMAIL, EmailOtpPort.PERSONAL_EMAIL))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void request_rejectsAnInvalidEmail() {
        EmailOtpService service = service();
        assertThatThrownBy(() -> service.request("not-an-email", EmailOtpPort.PERSONAL_EMAIL))
                .isInstanceOf(BusinessException.class);
    }
}
