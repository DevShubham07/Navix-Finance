package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.util.Masking;
import com.navix.common.verification.EmailOtpPort;
import com.navix.common.verification.OtpVerifierPort.OtpRequestResult;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import com.navix.notification.suppression.EmailSuppressionService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Personal-email OTP: generates a random code, delivers it via {@link EmailClient} (bypassing the
 * notification dispatcher, same reasoning as {@link PasswordResetService} — a code is a credential,
 * not an inbox item), and verifies it. Sibling of {@link BorrowerOtpService}, same in-memory
 * purpose-keyed store / TTL / attempt-cap shape, swapped to an email transport.
 *
 * <p>In-memory and single-instance — the same caveat {@link BorrowerOtpService} carries.
 */
@Service
public class EmailOtpService implements EmailOtpPort {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_SENDS = 5;
    private static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    /** Longer than the SMS OTP's TTL — email delivery is slower and less immediate than SMS. */
    private static final int TTL_SECONDS = 600;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailClient emailClient;
    private final EmailProperties emailProperties;
    private final EmailSuppressionService suppression;
    private final AttemptLimiter limiter;
    private final Map<String, Otp> store = new ConcurrentHashMap<>();

    private record Otp(String code, Instant expiresAt, int attempts) {
    }

    public EmailOtpService(EmailClient emailClient, EmailProperties emailProperties,
                           EmailSuppressionService suppression, AttemptLimiter limiter) {
        this.emailClient = emailClient;
        this.emailProperties = emailProperties;
        this.suppression = suppression;
        this.limiter = limiter;
    }

    private static String key(String purpose, String email) {
        return purpose + ":" + email;
    }

    @Override
    public OtpRequestResult request(String email, String purpose) {
        String address = normalize(email);
        String storeKey = key(purpose, address);
        // Same rationale as BorrowerOtpService: capped before the email goes out, so the store.put
        // below (which resets the attempt counter) can't be used to grind an unbounded number of
        // guesses by just re-requesting.
        limiter.hit("emailotp:" + storeKey, MAX_SENDS, SEND_WINDOW, SEND_WINDOW,
                "Too many email verification requests for this address.");

        String code = generate();
        store.put(storeKey, new Otp(code, Instant.now().plusSeconds(TTL_SECONDS), 0));

        boolean sent = false;
        if (suppression.isSuppressed(address)) {
            // A bounced/complained address must not be re-mailed — mirrors EmailSender's SUPPRESSED skip.
            log.info("EMAIL OTP suppressed to={} — not sent", Masking.maskEmail(address));
        } else if (Boolean.TRUE.equals(emailProperties.enabled())) {
            String subject = "Your DhanBoost verification code";
            String body = "Your DhanBoost email verification code is " + code + ". It is valid for "
                    + (TTL_SECONDS / 60) + " minutes. Do not share it with anyone.\n\n— DhanBoost";
            if ("log".equalsIgnoreCase(emailProperties.provider())) {
                // Dev/demo convenience, mirrors PasswordResetService's reset-link log line — no real
                // mail goes out with the log provider, so surface the code for local testing.
                log.info("DEV email OTP for {}: {}", Masking.maskEmail(address), code);
            }
            EmailResult result = emailClient.send(new EmailMessage(address, subject, body, null));
            sent = result.ok();
            if (!sent) {
                log.warn("email OTP to {} failed: {}", Masking.maskEmail(address), result.error());
            }
        }
        // Never reveal the code in the response — unlike BorrowerOtpService's mock/dev-echo, there is
        // no local-testing need to (the "log" provider above already surfaces it for that purpose).
        return new OtpRequestResult(sent, null, TTL_SECONDS);
    }

    @Override
    public boolean verify(String email, String code, String purpose) {
        String address = normalize(email);
        String storeKey = key(purpose, address);
        Otp otp = store.get(storeKey);
        if (otp == null || code == null) {
            return false;
        }
        if (Instant.now().isAfter(otp.expiresAt()) || otp.attempts() >= MAX_ATTEMPTS) {
            store.remove(storeKey);
            return false;
        }
        if (!otp.code().equals(code.trim())) {
            store.put(storeKey, new Otp(otp.code(), otp.expiresAt(), otp.attempts() + 1));
            return false;
        }
        store.remove(storeKey); // single-use
        return true;
    }

    private String generate() {
        return String.valueOf(100000 + RANDOM.nextInt(900000)); // 6 digits, no leading zero
    }

    private static String normalize(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new BusinessException("INVALID_EMAIL", "A valid email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
