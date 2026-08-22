package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/**
 * The branch that matters is "is this thing switched on" — everything downstream of it is one HTTP
 * call to Cloudflare, which is not worth a stubbed server. What must not regress is that an
 * unconfigured deployment stays wide open (dev, CI, e2e, the demo seed all run that way) and a
 * configured one refuses an empty token without a round-trip.
 */
class CaptchaVerifierTest {

    @Test
    void unconfiguredIsANoOp() {
        CaptchaVerifier verifier = new CaptchaVerifier("", "");
        assertThat(verifier.isEnabled()).isFalse();
        assertThatCode(() -> verifier.verify(null, "staff-login")).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify("anything", "staff-login")).doesNotThrowAnyException();
    }

    @Test
    void blankSecretIsTreatedAsUnset() {
        assertThat(new CaptchaVerifier("   ", "").isEnabled()).isFalse();
        assertThat(new CaptchaVerifier(null, null).isEnabled()).isFalse();
    }

    @Test
    void configuredRejectsAMissingToken() {
        CaptchaVerifier verifier = new CaptchaVerifier("a-secret", "");
        assertThat(verifier.isEnabled()).isTrue();
        for (String token : new String[] {null, "", "   ", "x".repeat(2049)}) {
            assertThatThrownBy(() -> verifier.verify(token, "staff-login"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "CAPTCHA_FAILED");
        }
    }
}
