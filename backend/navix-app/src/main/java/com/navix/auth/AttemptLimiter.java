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
 * <p>Counts EVERY attempt, not just failures. A success-resets-the-counter design needs bookkeeping
 * at three call sites to buy nothing: the caps here sit far above any legitimate use, so a real user
 * never notices the difference.
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

    private record Window(int count, Instant resetAt) {
    }

    /**
     * Count one attempt against {@code key}, throwing once more than {@code max} land inside
     * {@code window}. The window starts at the first attempt and does not slide.
     */
    public void hit(String key, int max, Duration window, String message) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (k, current) ->
                current == null || now.isAfter(current.resetAt())
                        ? new Window(1, now.plus(window))
                        : new Window(current.count() + 1, current.resetAt()));
        if (updated.count() > max) {
            throw new BusinessException("TOO_MANY_ATTEMPTS", message);
        }
    }
}
