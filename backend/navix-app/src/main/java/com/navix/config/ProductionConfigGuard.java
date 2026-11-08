package com.navix.config;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.sms.SmsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot with a demo-only setting unless {@code NAVIX_ALLOW_DEV_DEFAULTS=true} says this is
 * a throwaway local stack.
 *
 * <p>Deliberately not keyed on {@code NAVIX_ENV}: the live ECS task runs {@code NAVIX_ENV=dev} (it
 * selects the SSM path), so a check gated on {@code prod} never fired where it mattered.
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
 *   <li>{@code navix.esign.provider=mock} — reports every loan agreement as signed without anyone
 *       signing anything, so the file reaches disbursement on a signature that does not exist.</li>
 *   <li>{@code navix.email.provider=log} — password-reset links and email OTPs go to the log, so a
 *       log reader can take over any account.</li>
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
    private final FeatureFlagService featureFlags;

    /** Fail closed: absent means "treat this as real". Only local stacks and tests set it. */
    @Value("${navix.security.allow-dev-defaults:${NAVIX_ALLOW_DEV_DEFAULTS:false}}")
    private boolean allowDevDefaults;

    // The literal is shared with the constant above (a compile-time constant expression), so the
    // check can never drift from the default it is checking for.
    @Value("${navix.auth.secret:" + DEFAULT_SECRET + "}")
    private String secret;

    @Value("${navix.esign.provider:signzy}")
    private String esignProvider;

    @Value("${navix.digio.provider:digio}")
    private String digioProvider;

    @Value("${navix.payu.key:}")
    private String payuKey;

    @Value("${navix.payu.salt:}")
    private String payuSalt;

    @Value("${navix.payu.checkout-url:https://test.payu.in/_payment}")
    private String payuCheckoutUrl;

    @Value("${navix.payu.command-url:https://test.payu.in/merchant/postservice.php?form=2}")
    private String payuCommandUrl;

    @Value("${navix.email.provider:log}")
    private String emailProvider;

    @Value("${navix.email.enabled:true}")
    private boolean emailEnabled;

    @PostConstruct
    void check() {
        if (allowDevDefaults) {
            return;
        }
        if (DEFAULT_SECRET.equals(secret) || secret == null || secret.length() < MIN_SECRET_LENGTH) {
            // Length matters independently of the equality check: the key is a single unsalted
            // SHA-256 of this string, so AUTH_SECRET=changeme yields a "valid" 256-bit key with
            // eight characters of entropy behind it.
            throw new IllegalStateException("Built-in or too-short navix.auth.secret — set AUTH_SECRET "
                    + "to at least " + MIN_SECRET_LENGTH + " random characters (openssl rand -base64 48), "
                    + "or NAVIX_ALLOW_DEV_DEFAULTS=true on a throwaway local stack.");
        }
        if (sms.mock()) {
            throw new IllegalStateException("navix.sms.mock=true — the fixed mock OTP verifies for ANY "
                    + "mobile. Unset NAVIX_SMS_MOCK.");
        }
        if (sms.devEcho()) {
            throw new IllegalStateException("navix.sms.dev-echo=true — the OTP is returned in the HTTP "
                    + "response. Unset NAVIX_SMS_DEV_ECHO.");
        }
        if ("mock".equalsIgnoreCase(esignProvider)) {
            // Worse than a bypass: the borrower's file reaches disbursement carrying a PASS on the one
            // check that is a legal act, with no signature behind it and nothing to enforce later.
            throw new IllegalStateException("navix.esign.provider=mock — every loan agreement would be "
                    + "recorded as signed without a real Aadhaar e-signature. Unset NAVIX_ESIGN_PROVIDER.");
        }
        if (emailEnabled && "log".equalsIgnoreCase(emailProvider)) {
            throw new IllegalStateException("navix.email.provider=log — password-reset links and email "
                    + "OTPs would be written to the log and no mail sent. Set NAVIX_EMAIL_PROVIDER.");
        }
    }

    /**
     * Digio is checked <b>only when the {@code digio-mandate} flag is actually on</b>, and after the
     * context is up so the DB-backed flag can be read.
     *
     * <p>The eSign lesson above is about SILENT degradation: the mock reported agreements as signed while
     * the journey ran. With {@code digio-mandate} off the mandate step does not exist, nothing calls the
     * adapter, and nothing degrades — so an unconditional check only refused every unrelated prod deploy
     * until five {@code NAVIX_DIGIO_*} values existed in SSM for a disabled feature. With the flag on the
     * mock is exactly as dangerous as the eSign one (mandates recorded as registered, nothing filed at
     * NPCI), and this still fails the boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    void checkDigio() {
        if (allowDevDefaults || !"mock".equalsIgnoreCase(digioProvider)) {
            return;
        }
        if (featureFlags.isEnabled("digio-mandate", false)) {
            throw new IllegalStateException("navix.digio.provider=mock while the "
                    + "digio-mandate flag is ON — every eMandate would be recorded as registered without a "
                    + "real NPCI filing. Set NAVIX_DIGIO_PROVIDER=digio (and the NAVIX_DIGIO_* "
                    + "credentials), or turn the flag off.");
        }
    }

    /**
     * PayU is checked <b>only when the {@code payu-repayment} flag is actually on</b>, for the same reason as
     * Digio: with the flag off nothing calls PayU, so refusing the boot would block every unrelated deploy
     * over a disabled feature. With it on, a blank key/salt makes every checkout fail and a {@code test.payu.in}
     * URL would send real borrowers to a sandbox that takes no money while the loan stays unpaid.
     */
    @EventListener(ApplicationReadyEvent.class)
    void checkPayu() {
        if (allowDevDefaults || !featureFlags.isEnabled("payu-repayment", false)) {
            return;
        }
        if (payuKey == null || payuKey.isBlank() || payuSalt == null || payuSalt.isBlank()) {
            throw new IllegalStateException("The payu-repayment flag is ON but navix.payu.key/salt are blank. "
                    + "Set NAVIX_PAYU_KEY and NAVIX_PAYU_SALT (SSM /navix/<env>/navix/payu/...), or turn the flag off.");
        }
        if (payuCheckoutUrl.contains("test.payu.in") || payuCommandUrl.contains("test.payu.in")) {
            throw new IllegalStateException("The payu-repayment flag is ON but a PayU URL still points at "
                    + "test.payu.in. Set NAVIX_PAYU_CHECKOUT_URL and NAVIX_PAYU_COMMAND_URL to the live hosts, "
                    + "or turn the flag off.");
        }
    }
}
