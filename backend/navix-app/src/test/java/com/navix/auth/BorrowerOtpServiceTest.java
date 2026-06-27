package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.sms.SmsException;
import com.navix.sms.SmsProperties;
import com.navix.sms.UltronSmsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BorrowerOtpService}: SMS dispatch, dev-echo behaviour, single-use
 * verification, graceful gateway-failure handling, and the wrong-attempt lock-out cap.
 */
@ExtendWith(MockitoExtension.class)
class BorrowerOtpServiceTest {

    private static final String MOBILE = "9819000001";

    @Mock
    private UltronSmsClient smsClient;

    private static SmsProperties props(boolean devEcho) {
        return props(devEcho, false);
    }

    /** Build {@code SmsProperties} in its exact record field order. */
    private static SmsProperties props(boolean devEcho, boolean mock) {
        return new SmsProperties(
                "https://ultronsms.test/api/mt/", // baseUrl
                "user",                            // user
                "password",                        // password
                null,                              // apiKey
                "NAVIX",                           // senderId
                "Trans",                           // channel
                "route",                           // route
                "peid",                            // peid
                "dltTemplateId",                   // dltTemplateId
                "Your NAVIX code is {otp}. Valid {ttl} min.", // otpTemplate
                true,                              // enabled
                devEcho,                           // devEcho
                300,                               // otpTtlSeconds
                6,                                 // otpLength
                mock,                              // mock
                "123456"                           // mockCode
        );
    }

    @Test
    void mockMode_usesFixedCode_andNeverCallsSms() {
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(false, true));
        var result = service.request("9812345678");

        assertThat(result.sent()).isTrue();        // mock "delivery"
        assertThat(result.devCode()).isEqualTo("123456");
        verifyNoInteractions(smsClient);            // no real SMS
        assertThat(service.verify("9812345678", "123456")).isTrue();
        assertThat(service.verify("9999999999", "123456")).isTrue(); // fixed code works for any mobile
        assertThat(service.verify("9812345678", "000000")).isFalse();
    }

    @Test
    void request_sendsSms_andEchoesSixDigitCode_whenDevEchoOn() {
        when(smsClient.send(anyString(), anyString())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true));

        BorrowerOtpService.OtpRequest req = service.request(MOBILE);

        verify(smsClient).send(eq("91" + MOBILE), anyString());
        assertThat(req.sent()).isTrue();
        assertThat(req.devCode()).isNotNull().hasSize(6).containsOnlyDigits();
        assertThat(req.ttlSeconds()).isEqualTo(300);
        // the echoed code verifies
        assertThat(service.verify(MOBILE, req.devCode())).isTrue();
    }

    @Test
    void request_doesNotEchoCode_whenDevEchoOff() {
        when(smsClient.send(anyString(), anyString())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(false));

        BorrowerOtpService.OtpRequest req = service.request(MOBILE);

        assertThat(req.sent()).isTrue();
        assertThat(req.devCode()).isNull();
    }

    @Test
    void verify_isSingleUse_andRejectsWrongCode() {
        when(smsClient.send(anyString(), anyString())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true));

        String code = service.request(MOBILE).devCode();

        assertThat(service.verify(MOBILE, "000000")).isFalse(); // wrong code
        assertThat(service.verify(MOBILE, code)).isTrue();       // correct, consumes it
        assertThat(service.verify(MOBILE, code)).isFalse();      // already used → single-use
    }

    @Test
    void request_gracefullyHandlesSmsFailure_butStillEchoesUsableCode() {
        when(smsClient.send(anyString(), anyString()))
                .thenThrow(new SmsException("SMS gateway: error:Invalid template text"));
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true));

        BorrowerOtpService.OtpRequest req = service.request(MOBILE);

        assertThat(req.sent()).isFalse();          // dispatch failed, surfaced as sent=false
        assertThat(req.devCode()).isNotNull().hasSize(6);
        assertThat(service.verify(MOBILE, req.devCode())).isTrue(); // code still stored + usable
    }

    @Test
    void verify_locksOutAfterFiveWrongAttempts() {
        when(smsClient.send(anyString(), anyString())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true));

        String code = service.request(MOBILE).devCode();

        for (int i = 0; i < 5; i++) {
            assertThat(service.verify(MOBILE, "000000")).isFalse();
        }
        // even the correct code is now refused — attempt cap reached.
        assertThat(service.verify(MOBILE, code)).isFalse();
    }
}
