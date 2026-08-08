package com.navix.auth;

import com.navix.common.exception.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fixed-window attempt counter for the pre-auth endpoints — OTP sends and password logins are the
 * only routes reachable without a token, so the only ones an attacker can grind for free.
 *
 * <p>Counts every call it is given; whether a success should count is the caller's decision, made by
 * calling {@link #clear} after one. The login routes do (three strikes are three WRONG passwords);
 * the OTP send cap does not, because there it is SMS being rationed and a send that worked is
 * exactly the thing costing money.
 *
 * <p>This used to count successes everywhere, on the reasoning that the caps sat far above any real
 * use. That stopped being true when the login cap dropped to three: signing in on a second device,
 * or a test suite exercising the login form, spent the budget and locked the account out.
 *
 * <p>In-memory and single-instance, the same caveat {@link BorrowerOtpService} carries for its OTP
 * store — behind more than one task an attacker simply round-robins them. Move both to Redis
 * together if this ever scales out.
 */
@Component
public class AttemptLimiter {

    /** Keys come from the caller (a mobile / an email), so the map must not grow without bound. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    // ponytail: LRU eviction, so a flood of distinct keys can push a real one out early. Redis with
    // per-key TTL if that ever matters; for a single task at this traffic it does not.
    private final Map<String, Window> windows = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

    /**
     * @param count      attempts seen inside the current counting window
     * @param countUntil when those attempts are forgotten
     * @param blockUntil when the current cool-off ends, or null if not blocked
     */
    private record Window(int count, Instant countUntil, Instant blockUntil) {
        boolean blockedAt(Instant now) {
            return blockUntil != null && now.isBefore(blockUntil);
        }
    }

    /**
     * Count one attempt against {@code key}. Once more than {@code max} land inside
     * {@code countWindow}, the key is blocked for {@code blockFor} and the count starts over — so
     * the pattern is "three tries, wait, three more".
     *
     * <p><b>Counting and blocking are separate durations on purpose.</b> Folding them into one
     * (count 3 per 30s, block 30s) produced a limiter that never fired against a human: attempts
     * made more than 30s apart each landed in a fresh window, so someone typing passwords by hand —
     * or a browser driving the form — could guess forever and never be throttled once. Only a
     * scripted burst was ever caught, which is the easy half of the problem. The count therefore
     * persists for minutes while the cool-off stays short enough that a real user just waits.
     *
     * <p>Attempts made while blocked do <b>not</b> extend the block — someone hammering the endpoint
     * waits exactly as long as someone who stops, and cannot lock themselves out indefinitely.
     *
     * <p>{@code lead} is the first sentence only ("Too many sign-in attempts."); the wait is
     * appended from the live block, so the caller never hard-codes a duration the constants later
     * contradict. That mattered here: the message said "try again in a few minutes" for a
     * thirty-second block.
     */
    public void hit(String key, int max, Duration countWindow, Duration blockFor, String lead) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (k, current) -> {
            // Already serving a cool-off: leave it exactly as it is.
            if (current != null && current.blockedAt(now)) {
                return current;
            }
            // The counting window is what survives between slow attempts; the expired block does not.
            int count = current == null || now.isAfter(current.countUntil()) ? 1 : current.count() + 1;
            Instant countUntil = current == null || now.isAfter(current.countUntil())
                    ? now.plus(countWindow)
                    : current.countUntil();
            return count > max
                    // Over the line: start the cool-off and give them a clean slate on the far side.
                    ? new Window(0, now.plus(countWindow), now.plus(blockFor))
                    : new Window(count, countUntil, null);
        });
        if (updated.blockedAt(now)) {
            throw new BusinessException("TOO_MANY_ATTEMPTS",
                    lead + " Please try again in " + humanWait(now, updated.blockUntil()) + ".");
        }
    }

    /**
     * Forget {@code key}'s attempts — call after a successful authentication so a run of wrong
     * passwords ending in the right one leaves no penalty behind. Idempotent; unknown keys are fine.
     */
    public void clear(String key) {
        windows.remove(key);
    }

    /** "27 seconds" / "1 minute" / "4 minutes" — never "0 seconds", which reads as "retry now". */
    private static String humanWait(Instant now, Instant resetAt) {
        long seconds = Math.max(1, Duration.between(now, resetAt).getSeconds());
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60; // round up: "1 minute" beats "0 minutes"
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
