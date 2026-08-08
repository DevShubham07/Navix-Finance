package com.navix.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.sms.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The guard's whole value is that it trips, so each branch gets a check. Also pins the two
 * behaviours that keep the demo working: dev is never blocked, and a real prod config boots.
 */
class ProductionConfigGuardTest {

    private static final String DEFAULT_SECRET = "navix-local-dev-secret-change-me";
    private static final String STRONG_SECRET = "S6nQ2wYd8kLpR4vT7xZa1cE3gJ5mB9oU";  // 32 chars

    private static ProductionConfigGuard guard(String env, String secret, boolean mock, boolean devEcho) {
        ProductionConfigGuard g = new ProductionConfigGuard(sms(mock, devEcho));
        ReflectionTestUtils.setField(g, "env", env);
        ReflectionTestUtils.setField(g, "secret", secret);
        return g;
    }

    private static SmsProperties sms(boolean mock, boolean devEcho) {
        return new SmsProperties(null, null, null, null, null, null, null, null, null,
                java.util.Map.of(), null, null, true, devEcho, 300, 6, mock, "123456");
    }

    @Test
    void dev_bootsWithEveryDemoSettingOn() {
        assertThatCode(() -> guard("dev", DEFAULT_SECRET, true, true).check())
                .doesNotThrowAnyException();
    }

    @Test
    void prod_rejectsTheBuiltInSecret() {
        assertThatThrownBy(() -> guard("prod", DEFAULT_SECRET, false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_SECRET");
    }

    /** SHA-256 widens any string to a 256-bit key, so length has to be checked separately. */
    @Test
    void prod_rejectsAShortSecret() {
        assertThatThrownBy(() -> guard("prod", "changeme", false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too-short");
    }

    @Test
    void prod_rejectsMockSms_whichVerifiesAnyMobile() {
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, true, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_SMS_MOCK");
    }

    @Test
    void prod_rejectsDevEcho_whichReturnsTheOtpInTheResponse() {
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_SMS_DEV_ECHO");
    }

    @Test
    void prod_bootsWhenProperlyConfigured() {
        assertThatCode(() -> guard("prod", STRONG_SECRET, false, false).check())
                .doesNotThrowAnyException();
    }
}
