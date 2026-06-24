package com.navix.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the in-memory demo {@link OtpService}: generate→verify success, wrong-code
 * failure, single-use consumption, attempt limiting, expiry and resend cooldown.
 */
class OtpServiceTest {

    private static final String MOBILE = "9876543210";

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService();
    }

    @Test
    void generateThenVerifySucceeds() {
        String code = otpService.generate(MOBILE);

        assertThat(code).hasSize(6).containsOnlyDigits();
        assertThat(otpService.verify(MOBILE, code)).isTrue();
    }

    @Test
    void verifyWithWrongCodeFails() {
        String code = otpService.generate(MOBILE);

        assertThatThrownBy(() -> otpService.verify(MOBILE, wrong(code)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Incorrect");
    }

    @Test
    void codeIsSingleUse() {
        String code = otpService.generate(MOBILE);
        assertThat(otpService.verify(MOBILE, code)).isTrue();

        assertThatThrownBy(() -> otpService.verify(MOBILE, code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active OTP");
    }

    @Test
    void verifyWithoutGenerateFails() {
        assertThatThrownBy(() -> otpService.verify(MOBILE, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active OTP");
    }

    @Test
    void attemptsAreLimited() {
        String code = otpService.generate(MOBILE);
        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> otpService.verify(MOBILE, wrong(code)))
                    .isInstanceOf(BusinessException.class);
        }
        // Exhausted: even the correct code no longer verifies.
        assertThatThrownBy(() -> otpService.verify(MOBILE, code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active OTP");
    }

    @Test
    void expiredCodeFails() throws Exception {
        String code = otpService.generate(MOBILE);
        // Force the stored entry to be expired by reflecting the internal map.
        expireStoredOtp(MOBILE);

        assertThatThrownBy(() -> otpService.verify(MOBILE, code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void resendWithinCooldownIsRejected() {
        otpService.generate(MOBILE);

        assertThatThrownBy(() -> otpService.resend(MOBILE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("wait");
    }

    @Test
    void blankDestinationIsRejected() {
        assertThatThrownBy(() -> otpService.generate(" "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Destination");
    }

    private static String wrong(String code) {
        // Deterministically produce a different 6-digit string.
        return code.equals("000000") ? "111111" : "000000";
    }

    /** Reflectively backdate the stored OTP's expiry so verify() sees it as expired. */
    @SuppressWarnings("unchecked")
    private void expireStoredOtp(String destination) throws Exception {
        var storeField = OtpService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        ConcurrentHashMap<String, Object> store =
                (ConcurrentHashMap<String, Object>) storeField.get(otpService);
        Object otp = store.get(destination);
        var expiresAt = otp.getClass().getDeclaredField("expiresAt");
        expiresAt.setAccessible(true);
        expiresAt.set(otp, Instant.now().minusSeconds(1));
    }
}
