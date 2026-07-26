package com.navix.config;

import com.navix.sms.SmsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot when {@code NAVIX_ENV=prod} is paired with a demo-only setting.
 *
 * <p>The demo features themselves stay — they are documented and load-bearing for the demo
 * (CLAUDE.md §4.4). What is missing is a tripwire: each one fails OPEN, silently, and the failure
 * looks exactly like a working system.
 *
 * <ul>
 *   <li>the built-in {@code navix.auth.secret} — it is committed, so anyone with the repo can forge
 *       a token for any customer or staff member;</li>
 *   <li>{@code navix.sms.mock} — the fixed code verifies for ANY mobile with no stored OTP, which is
 *       a total authentication bypass, not merely a convenience;</li>
 *   <li>{@code navix.sms.dev-echo} — returns the OTP in the HTTP response, same class of bypass.</li>
 * </ul>
 *
 * <p>Thrown from {@code @PostConstruct}, so it fires during bean initialisation — before the web
 * server accepts a single request.
 *
 * <p>Lives in {@code navix-app} rather than {@code JwtService}: that class is in {@code navix-common}
 * and is constructed directly by unit tests (no {@code @PostConstruct} would run), and it knows
 * nothing about the SMS properties. One validator covering both is the smaller, more findable diff.
 */
@Component
@RequiredArgsConstructor
public class ProductionConfigGuard {

    /** Must stay identical to the {@code @Value} default below and to JwtService's. */
    private static final String DEFAULT_SECRET = "navix-local-dev-secret-change-me";

    /** Below this, the secret is guessable regardless of the SHA-256 that widens it to 256 bits. */
    private static final int MIN_SECRET_LENGTH = 32;

    private final SmsProperties sms;

    @Value("${NAVIX_ENV:dev}")
    private String env;

    // The literal is shared with the constant above (a compile-time constant expression), so the
    // check can never drift from the default it is checking for.
    @Value("${navix.auth.secret:" + DEFAULT_SECRET + "}")
    private String secret;

    @PostConstruct
    void check() {
        if (!"prod".equalsIgnoreCase(env)) {
            return;
        }
        if (DEFAULT_SECRET.equals(secret) || secret == null || secret.length() < MIN_SECRET_LENGTH) {
            // Length matters independently of the equality check: the key is a single unsalted
            // SHA-256 of this string, so AUTH_SECRET=changeme yields a "valid" 256-bit key with
            // eight characters of entropy behind it.
            throw new IllegalStateException("NAVIX_ENV=prod with the built-in or too-short "
                    + "navix.auth.secret — set AUTH_SECRET to at least " + MIN_SECRET_LENGTH
                    + " random characters (openssl rand -base64 48).");
        }
        if (sms.mock()) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.sms.mock=true — the fixed mock "
                    + "OTP verifies for ANY mobile. Unset NAVIX_SMS_MOCK.");
        }
        if (sms.devEcho()) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.sms.dev-echo=true — the OTP is "
                    + "returned in the HTTP response. Unset NAVIX_SMS_DEV_ECHO.");
        }
    }
}
