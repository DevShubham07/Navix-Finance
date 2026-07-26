package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AttemptLimiter}: the cap, the error it raises, and per-key isolation. */
class AttemptLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String MESSAGE = "Too many attempts.";

    @Test
    void allowsUpToTheCap_thenThrows() {
        AttemptLimiter limiter = new AttemptLimiter();

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> limiter.hit("otp:9819000001", 3, WINDOW, MESSAGE))
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.hit("otp:9819000001", 3, WINDOW, MESSAGE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many attempts");
    }

    @Test
    void raisesTooManyAttempts_soTheBffCanSurfaceIt() {
        AttemptLimiter limiter = new AttemptLimiter();
        limiter.hit("k", 1, WINDOW, MESSAGE);

        assertThatThrownBy(() -> limiter.hit("k", 1, WINDOW, MESSAGE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS"));
    }

    /** One borrower exhausting their cap must never lock anyone else out. */
    @Test
    void countsEachKeyIndependently() {
        AttemptLimiter limiter = new AttemptLimiter();
        limiter.hit("otp:9819000001", 1, WINDOW, MESSAGE);

        assertThatCode(() -> limiter.hit("otp:9919000002", 1, WINDOW, MESSAGE))
                .doesNotThrowAnyException();
    }
}
