package com.navix.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;

import com.navix.common.featureflag.FeatureFlagService;
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

    private static ProductionConfigGuard guard(boolean allowDevDefaults, String secret, boolean mock, boolean devEcho) {
        return guard(allowDevDefaults, secret, mock, devEcho, "signzy");
    }

    private static ProductionConfigGuard guard(boolean allowDevDefaults, String secret, boolean mock, boolean devEcho,
                                               String esignProvider) {
        ProductionConfigGuard g = new ProductionConfigGuard(sms(mock, devEcho), flags(false));
        ReflectionTestUtils.setField(g, "allowDevDefaults", allowDevDefaults);
        ReflectionTestUtils.setField(g, "emailProvider", "resend");
        ReflectionTestUtils.setField(g, "emailEnabled", true);
        ReflectionTestUtils.setField(g, "secret", secret);
        ReflectionTestUtils.setField(g, "esignProvider", esignProvider);
        ReflectionTestUtils.setField(g, "digioProvider", "digio");
        return g;
    }

    /** The digio clause reads the {@code digio-mandate} flag; everything else ignores it. */
    private static FeatureFlagService flags(boolean digioMandate) {
        FeatureFlagService f = mock(FeatureFlagService.class);
        org.mockito.Mockito.lenient().when(f.isEnabled("digio-mandate", false)).thenReturn(digioMandate);
        return f;
    }

    private static SmsProperties sms(boolean mock, boolean devEcho) {
        return new SmsProperties(null, null, null, null, null, null, null, null, null,
                java.util.Map.of(), null, null, true, devEcho, 300, 6, mock, "123456");
    }

    @Test
    void dev_bootsWithEveryDemoSettingOn() {
        assertThatCode(() -> guard(true, DEFAULT_SECRET, true, true).check())
                .doesNotThrowAnyException();
    }

    @Test
    void prod_rejectsTheBuiltInSecret() {
        assertThatThrownBy(() -> guard(false, DEFAULT_SECRET, false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_SECRET");
    }

    /** SHA-256 widens any string to a 256-bit key, so length has to be checked separately. */
    @Test
    void prod_rejectsAShortSecret() {
        assertThatThrownBy(() -> guard(false, "changeme", false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too-short");
    }

    @Test
    void prod_rejectsMockSms_whichVerifiesAnyMobile() {
        assertThatThrownBy(() -> guard(false, STRONG_SECRET, true, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_SMS_MOCK");
    }

    @Test
    void prod_rejectsDevEcho_whichReturnsTheOtpInTheResponse() {
        assertThatThrownBy(() -> guard(false, STRONG_SECRET, false, true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_SMS_DEV_ECHO");
    }

    @Test
    void prod_rejectsMockEsign_whichSignsNothing() {
        assertThatThrownBy(() -> guard(false, STRONG_SECRET, false, false, "mock").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_ESIGN_PROVIDER");
    }

    private static ProductionConfigGuard digioGuard(boolean allowDevDefaults, boolean flagOn) {
        ProductionConfigGuard g = new ProductionConfigGuard(sms(false, false), flags(flagOn));
        ReflectionTestUtils.setField(g, "allowDevDefaults", allowDevDefaults);
        ReflectionTestUtils.setField(g, "emailProvider", "resend");
        ReflectionTestUtils.setField(g, "emailEnabled", true);
        ReflectionTestUtils.setField(g, "secret", STRONG_SECRET);
        ReflectionTestUtils.setField(g, "esignProvider", "signzy");
        ReflectionTestUtils.setField(g, "digioProvider", "mock");
        return g;
    }

    @Test
    void prod_rejectsMockDigio_whenTheMandateFlagIsOn() {
        assertThatThrownBy(digioGuard(false, true)::checkDigio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_DIGIO_PROVIDER");
    }

    /** The feature is off: nothing calls the adapter, so a prod deploy must not be blocked for it. */
    @Test
    void prod_bootsWithMockDigio_whenTheMandateFlagIsOff() {
        assertThatCode(digioGuard(false, false)::checkDigio).doesNotThrowAnyException();
        assertThatCode(digioGuard(false, false)::check).doesNotThrowAnyException();
    }

    @Test
    void dev_bootsWithMockDigio() {
        assertThatCode(digioGuard(true, true)::checkDigio).doesNotThrowAnyException();
    }

    @Test
    void dev_bootsWithMockEsign_whichTheDemoSeedNeeds() {
        assertThatCode(() -> guard(true, DEFAULT_SECRET, true, true, "mock").check())
                .doesNotThrowAnyException();
    }

    @Test
    void prod_rejectsLogEmail_whichWritesResetLinksToTheLog() {
        ProductionConfigGuard g = guard(false, STRONG_SECRET, false, false);
        ReflectionTestUtils.setField(g, "emailProvider", "log");
        assertThatThrownBy(g::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_EMAIL_PROVIDER");
    }

    @Test
    void prod_bootsWhenProperlyConfigured() {
        assertThatCode(() -> guard(false, STRONG_SECRET, false, false).check())
                .doesNotThrowAnyException();
    }

    private static ProductionConfigGuard payuGuard(boolean dev, boolean flagOn, String key, String salt, String url) {
        FeatureFlagService f = mock(FeatureFlagService.class);
        org.mockito.Mockito.lenient().when(f.isEnabled("payu-repayment", false)).thenReturn(flagOn);
        ProductionConfigGuard g = new ProductionConfigGuard(sms(false, false), f);
        ReflectionTestUtils.setField(g, "allowDevDefaults", dev);
        ReflectionTestUtils.setField(g, "payuKey", key);
        ReflectionTestUtils.setField(g, "payuSalt", salt);
        ReflectionTestUtils.setField(g, "payuCheckoutUrl", url);
        ReflectionTestUtils.setField(g, "payuCommandUrl", "https://info.payu.in/merchant/postservice.php?form=2");
        return g;
    }

    @Test
    void payu_flagOff_neverBlocksADeploy() {
        assertThatCode(payuGuard(false, false, "", "", "https://test.payu.in/_payment")::checkPayu)
                .doesNotThrowAnyException();
    }

    @Test
    void payu_flagOn_refusesBlankCredentialsAndTestHosts() {
        assertThatThrownBy(payuGuard(false, true, "", "s", "https://secure.payu.in/_payment")::checkPayu)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NAVIX_PAYU_KEY");
        assertThatThrownBy(payuGuard(false, true, "k", "s", "https://test.payu.in/_payment")::checkPayu)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test.payu.in");
        assertThatCode(payuGuard(false, true, "k", "s", "https://secure.payu.in/_payment")::checkPayu)
                .doesNotThrowAnyException();
        assertThatCode(payuGuard(true, true, "", "", "https://test.payu.in/_payment")::checkPayu)
                .doesNotThrowAnyException();
    }
}
