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
            assertThatCode(() -> limiter.hit("otp:9819000001", 3, WINDOW, WINDOW, MESSAGE))
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.hit("otp:9819000001", 3, WINDOW, WINDOW, MESSAGE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many attempts");
    }

    @Test
    void raisesTooManyAttempts_soTheBffCanSurfaceIt() {
        AttemptLimiter limiter = new AttemptLimiter();
        limiter.hit("k", 1, WINDOW, WINDOW, MESSAGE);

        assertThatThrownBy(() -> limiter.hit("k", 1, WINDOW, WINDOW, MESSAGE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS"));
    }

    /** One borrower exhausting their cap must never lock anyone else out. */
    @Test
    void countsEachKeyIndependently() {
        AttemptLimiter limiter = new AttemptLimiter();
        limiter.hit("otp:9819000001", 1, WINDOW, WINDOW, MESSAGE);

        assertThatCode(() -> limiter.hit("otp:9919000002", 1, WINDOW, WINDOW, MESSAGE))
                .doesNotThrowAnyException();
    }

    /**
     * The block must LIFT on its own. This is the half of the contract the sign-in cap actually
     * depends on now: three tries then a short cool-off is only humane if the cool-off ends.
     */
    @Test
    void allowsAgainOnceTheWindowHasPassed() throws Exception {
        AttemptLimiter limiter = new AttemptLimiter();
        Duration shortWindow = Duration.ofMillis(150);

        limiter.hit("staff:a@b.example", 1, shortWindow, shortWindow, MESSAGE);
        assertThatThrownBy(() -> limiter.hit("staff:a@b.example", 1, shortWindow, shortWindow, MESSAGE))
                .isInstanceOf(BusinessException.class);

        Thread.sleep(200);

        assertThatCode(() -> limiter.hit("staff:a@b.example", 1, shortWindow, shortWindow, MESSAGE))
                .doesNotThrowAnyException();
    }

    /**
     * Retrying while blocked must not push the reset further out, or a user hammering the button
     * would extend their own lock-out indefinitely and never understand why.
     */
    @Test
    void attemptsWhileBlockedDoNotExtendTheWait() throws Exception {
        AttemptLimiter limiter = new AttemptLimiter();
        Duration shortWindow = Duration.ofMillis(250);

        limiter.hit("staff:c@d.example", 1, shortWindow, shortWindow, MESSAGE);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.hit("staff:c@d.example", 1, shortWindow, shortWindow, MESSAGE))
                    .isInstanceOf(BusinessException.class);
            Thread.sleep(20);
        }
        Thread.sleep(200);

        assertThatCode(() -> limiter.hit("staff:c@d.example", 1, shortWindow, shortWindow, MESSAGE))
                .doesNotThrowAnyException();
    }

    /**
     * The wait comes from the live window rather than the caller's prose. The old message promised
     * "a few minutes" for what is now a thirty-second block — the sort of copy that goes stale the
     * moment a constant changes.
     */
    @Test
    void messageNamesTheActualRemainingWait() {
        AttemptLimiter limiter = new AttemptLimiter();
        limiter.hit("staff:e@f.example", 1, Duration.ofSeconds(30), Duration.ofSeconds(30), "Too many sign-in attempts.");

        assertThatThrownBy(() -> limiter.hit("staff:e@f.example", 1, Duration.ofSeconds(30),
                Duration.ofSeconds(30), "Too many sign-in attempts."))
                .hasMessageContaining("Too many sign-in attempts.")
                .hasMessageContaining("Please try again in")
                .hasMessageContaining("seconds");
    }

    /**
     * The one that matters most, and the one whose absence let a broken limiter ship: attempts spread
     * further apart than the COOL-OFF must still add up.
     *
     * <p>With a single duration doing both jobs (count 3 per 30s, block 30s) this was wide open — a
     * person typing passwords by hand, or a browser driving the login form, put ~12s between tries,
     * so every attempt landed in a fresh counting window and the throttle never fired once. Only a
     * scripted burst was ever caught. Here the cool-off is tiny and the counting window is long: the
     * attempts are deliberately spaced wider than the cool-off, and must still trip the cap.
     */
    @Test
    void countsAttemptsSpacedWiderThanTheCoolOff() throws Exception {
        AttemptLimiter limiter = new AttemptLimiter();
        Duration countWindow = Duration.ofSeconds(30); // long memory
        Duration coolOff = Duration.ofMillis(50);      // short wait

        for (int i = 0; i < 3; i++) {
            final int n = i;
            assertThatCode(() -> limiter.hit("staff:slow@x.example", 3, countWindow, coolOff, MESSAGE))
                    .as("attempt %d, spaced wider than the cool-off", n + 1)
                    .doesNotThrowAnyException();
            Thread.sleep(120); // > coolOff, so a single-duration limiter would forget this attempt
        }

        assertThatThrownBy(() -> limiter.hit("staff:slow@x.example", 3, countWindow, coolOff, MESSAGE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many attempts");
    }

    /**
     * A successful sign-in wipes the slate: two wrong passwords then the right one must not leave a
     * user one mistake away from a lock-out for the rest of the window.
     */
    @Test
    void clearForgetsEarlierAttempts() {
        AttemptLimiter limiter = new AttemptLimiter();
        Duration window = Duration.ofSeconds(30);

        limiter.hit("staff:g@h.example", 3, window, window, MESSAGE);
        limiter.hit("staff:g@h.example", 3, window, window, MESSAGE);
        limiter.clear("staff:g@h.example"); // the third try was the right password

        // A full three are available again immediately.
        for (int i = 0; i < 3; i++) {
            final int n = i;
            assertThatCode(() -> limiter.hit("staff:g@h.example", 3, window, window, MESSAGE))
                    .as("attempt %d after clear", n + 1)
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> limiter.hit("staff:g@h.example", 3, window, window, MESSAGE))
                .isInstanceOf(BusinessException.class);
    }

    /** Clearing a key nobody has touched must not blow up. */
    @Test
    void clearIsSafeOnAnUnknownKey() {
        AttemptLimiter limiter = new AttemptLimiter();
        assertThatCode(() -> limiter.clear("staff:never-seen")).doesNotThrowAnyException();
    }

    /** A long window reads in minutes, not "900 seconds" — the OTP send cap still uses one. */
    @Test
    void longWaitsAreReportedInMinutes() {
        AttemptLimiter limiter = new AttemptLimiter();
        limiter.hit("otp:9819000003", 1, Duration.ofMinutes(15), Duration.ofMinutes(15), MESSAGE);

        assertThatThrownBy(() -> limiter.hit("otp:9819000003", 1, Duration.ofMinutes(15), Duration.ofMinutes(15), MESSAGE))
                .hasMessageContaining("15 minutes");
    }
}
