package com.navix.onboarding.service;

import com.navix.common.exception.BusinessException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * NAVIX's own OTP service (send + verify, with resend cooldown).
 *
 * <p>DEMO implementation: there is no OTP database table (and none may be added), so codes are
 * held in an in-memory {@link ConcurrentHashMap} keyed by mobile/destination. Each entry holds a
 * 6-digit code, an expiry, an attempt counter and the last-issued timestamp for cooldown. Codes
 * are single-use and attempt-limited. For the demo the generated code is returned to the caller
 * (no real SMS/email provider is wired). This class is pure/in-memory and unit-testable directly.
 */
@Service
public class OtpService {

    /** OTP validity window. */
    static final Duration TTL = Duration.ofMinutes(5);
    /** Minimum gap between (re)issues for the same destination. */
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    /** Maximum verify attempts before the code is invalidated. */
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, Otp> store = new ConcurrentHashMap<>();

    /** A live OTP for one destination. Mutable (attempt counter) so guarded under the map. */
    private static final class Otp {
        private final String code;
        private final Instant issuedAt;
        private final Instant expiresAt;
        private int attempts;

        private Otp(String code, Instant issuedAt, Instant expiresAt) {
            this.code = code;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Generate, store and (for the demo) return a fresh 6-digit OTP for the destination.
     * Enforces the resend cooldown against any still-active code.
     *
     * @param destination mobile number (or email) the OTP is bound to
     * @return the generated 6-digit code (demo convenience — would be delivered out-of-band live)
     * @throws BusinessException if a code was issued within the resend cooldown
     */
    public String generate(String destination) {
        requireDestination(destination);
        Instant now = Instant.now();
        Otp existing = store.get(destination);
        if (existing != null
                && now.isBefore(existing.issuedAt.plus(RESEND_COOLDOWN))) {
            throw new BusinessException("OTP_COOLDOWN",
                    "Please wait before requesting another OTP");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        store.put(destination, new Otp(code, now, now.plus(TTL)));
        return code;
    }

    /**
     * Resend an OTP for the destination, honouring the cooldown. A new code is issued (the
     * previous one is replaced) so a single code is ever active per destination.
     *
     * @return the freshly issued 6-digit code
     */
    public String resend(String destination) {
        return generate(destination);
    }

    /**
     * Verify a supplied code against the active OTP for the destination. Codes are single-use:
     * a successful verification consumes the code. Failures increment the attempt counter and,
     * once {@link #MAX_ATTEMPTS} is reached, invalidate the code.
     *
     * @return {@code true} on success
     * @throws BusinessException when no code is active, it has expired, attempts are exhausted,
     *                           or the supplied code does not match
     */
    public boolean verify(String destination, String code) {
        requireDestination(destination);
        Otp otp = store.get(destination);
        if (otp == null) {
            throw new BusinessException("OTP_NOT_FOUND", "No active OTP for this destination");
        }
        if (Instant.now().isAfter(otp.expiresAt)) {
            store.remove(destination);
            throw new BusinessException("OTP_EXPIRED", "OTP has expired, request a new one");
        }
        if (otp.attempts >= MAX_ATTEMPTS) {
            store.remove(destination);
            throw new BusinessException("OTP_ATTEMPTS_EXCEEDED",
                    "Too many incorrect attempts, request a new OTP");
        }
        if (!otp.code.equals(code)) {
            otp.attempts++;
            if (otp.attempts >= MAX_ATTEMPTS) {
                store.remove(destination);
            }
            throw new BusinessException("OTP_INVALID", "Incorrect OTP");
        }
        store.remove(destination); // single-use: consume on success
        return true;
    }

    private static void requireDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new BusinessException("OTP_DESTINATION_REQUIRED", "Destination is required");
        }
    }
}
