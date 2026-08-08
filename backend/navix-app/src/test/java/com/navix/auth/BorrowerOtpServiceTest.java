package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.verification.OtpVerifierPort;
import com.navix.sms.SmsException;
import com.navix.sms.SmsProperties;
import com.navix.sms.UltronSmsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BorrowerOtpService}: SMS dispatch, dev-echo behaviour, single-use
 * verification, graceful gateway-failure handling, the wrong-attempt lock-out cap, and
 * purpose-isolation (a LOGIN and a BUREAU_CONSENT OTP for the same mobile must not collide).
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
                "DhanBoost",                           // senderId
                "Trans",                           // channel
                "route",                           // route
                "peid",                            // peid
                "dltTemplateId",                   // dltTemplateId
                java.util.Map.of(),                // dltTemplateIds
                "Your DhanBoost code is {otp}. Valid {ttl} min.", // otpTemplate
                null,                               // bureauConsentOtpTemplate
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
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(false, true), new AttemptLimiter());
        var result = service.request("9812345678", OtpVerifierPort.LOGIN);

        assertThat(result.sent()).isTrue();        // mock "delivery"
        assertThat(result.devCode()).isEqualTo("123456");
        verifyNoInteractions(smsClient);            // no real SMS
        assertThat(service.verify("9812345678", "123456", OtpVerifierPort.LOGIN)).isTrue();
        assertThat(service.verify("9999999999", "123456", OtpVerifierPort.LOGIN)).isTrue(); // fixed code works for any mobile
        assertThat(service.verify("9812345678", "000000", OtpVerifierPort.LOGIN)).isFalse();
    }

    @Test
    void request_sendsSms_andEchoesSixDigitCode_whenDevEchoOn() {
        when(smsClient.send(anyString(), anyString(), any())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true), new AttemptLimiter());

        OtpVerifierPort.OtpRequestResult req = service.request(MOBILE, OtpVerifierPort.LOGIN);

        verify(smsClient).send(eq("91" + MOBILE), anyString(), eq(null));
        assertThat(req.sent()).isTrue();
        assertThat(req.devCode()).isNotNull().hasSize(6).containsOnlyDigits();
        assertThat(req.ttlSeconds()).isEqualTo(300);
        // the echoed code verifies
        assertThat(service.verify(MOBILE, req.devCode(), OtpVerifierPort.LOGIN)).isTrue();
    }

    @Test
    void request_doesNotEchoCode_whenDevEchoOff() {
        when(smsClient.send(anyString(), anyString(), any())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(false), new AttemptLimiter());

        OtpVerifierPort.OtpRequestResult req = service.request(MOBILE, OtpVerifierPort.LOGIN);

        assertThat(req.sent()).isTrue();
        assertThat(req.devCode()).isNull();
    }

    @Test
    void verify_isSingleUse_andRejectsWrongCode() {
        when(smsClient.send(anyString(), anyString(), any())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true), new AttemptLimiter());

        String code = service.request(MOBILE, OtpVerifierPort.LOGIN).devCode();

        assertThat(service.verify(MOBILE, "000000", OtpVerifierPort.LOGIN)).isFalse(); // wrong code
        assertThat(service.verify(MOBILE, code, OtpVerifierPort.LOGIN)).isTrue();       // correct, consumes it
        assertThat(service.verify(MOBILE, code, OtpVerifierPort.LOGIN)).isFalse();      // already used → single-use
    }

    @Test
    void request_gracefullyHandlesSmsFailure_butStillEchoesUsableCode() {
        when(smsClient.send(anyString(), anyString(), any()))
                .thenThrow(new SmsException("SMS gateway: error:Invalid template text"));
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true), new AttemptLimiter());

        OtpVerifierPort.OtpRequestResult req = service.request(MOBILE, OtpVerifierPort.LOGIN);

        assertThat(req.sent()).isFalse();          // dispatch failed, surfaced as sent=false
        assertThat(req.devCode()).isNotNull().hasSize(6);
        assertThat(service.verify(MOBILE, req.devCode(), OtpVerifierPort.LOGIN)).isTrue(); // code still stored + usable
    }

    @Test
    void verify_locksOutAfterFiveWrongAttempts() {
        when(smsClient.send(anyString(), anyString(), any())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true), new AttemptLimiter());

        String code = service.request(MOBILE, OtpVerifierPort.LOGIN).devCode();

        for (int i = 0; i < 5; i++) {
            assertThat(service.verify(MOBILE, "000000", OtpVerifierPort.LOGIN)).isFalse();
        }
        // even the correct code is now refused — attempt cap reached.
        assertThat(service.verify(MOBILE, code, OtpVerifierPort.LOGIN)).isFalse();
    }

    @Test
    void loginAndBureauConsentOtps_forSameMobile_doNotCollide() {
        when(smsClient.send(anyString(), anyString(), any())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true), new AttemptLimiter());

        String loginCode = service.request(MOBILE, OtpVerifierPort.LOGIN).devCode();
        String bureauCode = service.request(MOBILE, OtpVerifierPort.BUREAU_CONSENT).devCode();

        // Requesting the bureau-consent OTP must not have clobbered the pending login code.
        assertThat(bureauCode).isNotNull();
        assertThat(service.verify(MOBILE, loginCode, OtpVerifierPort.LOGIN)).isTrue();
        // The bureau-consent code is a separate entry, still verifiable under its own purpose.
        assertThat(service.verify(MOBILE, bureauCode, OtpVerifierPort.BUREAU_CONSENT)).isTrue();
        // Cross-purpose verification must fail even with the right digits.
        String anotherLogin = service.request(MOBILE, OtpVerifierPort.LOGIN).devCode();
        assertThat(service.verify(MOBILE, anotherLogin, OtpVerifierPort.BUREAU_CONSENT)).isFalse();
    }

    @Test
    void loginAndBureauConsentOtps_haveSeparateSendBudgets() {
        when(smsClient.send(anyString(), anyString(), any())).thenReturn("JOB-1");
        BorrowerOtpService service = new BorrowerOtpService(smsClient, props(true), new AttemptLimiter());

        // Exhaust the LOGIN send budget (MAX_SENDS = 5) for this mobile.
        for (int i = 0; i < 5; i++) {
            service.request(MOBILE, OtpVerifierPort.LOGIN);
        }
        // The BUREAU_CONSENT purpose has its own budget and must still be able to send.
        OtpVerifierPort.OtpRequestResult bureauReq = service.request(MOBILE, OtpVerifierPort.BUREAU_CONSENT);
        assertThat(bureauReq.sent()).isTrue();
    }
}
