package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.sms.SmsException;
import com.navix.sms.SmsProperties;
import com.navix.sms.UltronSmsClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Borrower mobile-OTP: generates a random code, delivers it via the {@link UltronSmsClient}
 * SMS gateway, and verifies it. Replaces the old fixed-{@code 123456} mock.
 *
 * <p>Codes are held in-memory (single-instance demo; move to Redis/DB for HA), keyed by
 * <b>purpose + the normalized 10-digit mobile</b>, with a TTL and a small attempt cap — the purpose
 * dimension keeps e.g. a login OTP and a bureau-consent OTP for the same mobile from clobbering each
 * other's stored code or rate-limit budget (see {@link OtpVerifierPort#LOGIN} /
 * {@link OtpVerifierPort#BUREAU_CONSENT}). The OTP is never logged or returned — except when
 * {@code navix.sms.dev-echo=true} (local testing without a handset).
 */
@Service
@RequiredArgsConstructor
public class BorrowerOtpService implements OtpVerifierPort {

    private static final Logger log = LoggerFactory.getLogger(BorrowerOtpService.class);
    private static final int MAX_ATTEMPTS = 5;
    /** Sends per mobile+purpose per {@link #SEND_WINDOW}. The login screen allows 1 initial + 3 resends. */
    private static final int MAX_SENDS = 5;
    private static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UltronSmsClient smsClient;
    private final SmsProperties props;
    private final AttemptLimiter limiter;
    private final Map<String, Otp> store = new ConcurrentHashMap<>();

    private record Otp(String code, Instant expiresAt, int attempts) {
    }

    private static String key(String purpose, String number) {
        return purpose + ":" + number;
    }

    /** Generate + store an OTP for {@code mobile}, scoped to {@code purpose}, and send it via SMS. */
    @Override
    public OtpRequestResult request(String mobile, String purpose) {
        String number = normalize(mobile);
        String storeKey = key(purpose, number);
        // Capped before the SMS goes out. This bounds two things at once: the gateway spend (and the
        // handset-bombing that comes free with it), and the store.put below, which resets the
        // MAX_ATTEMPTS guess counter — so without a send cap, guesses against a 6-digit code are
        // unbounded no matter what MAX_ATTEMPTS says.
        // Deliberately NOT loosened alongside the login cap: a send costs real money and rings a
        // real handset, so the thing being rationed here is SMS, not guesses. Lead sentence only —
        // the limiter appends the remaining wait.
        // Same duration for counting and cool-off: unlike the login cap this one is a hard ration on
        // sends, so "you have had your five this quarter-hour" is exactly the intended behaviour.
        // Keyed by purpose too, so a bureau-consent send doesn't burn the login send budget or vice versa.
        limiter.hit("otp:" + storeKey, MAX_SENDS, SEND_WINDOW, SEND_WINDOW,
                "Too many OTP requests for this number.");
        // Mock mode (demo/testing): a fixed code, no SMS, no DLT — testers just enter mockCode().
        String code = props.mock() ? mockCode() : generate();
        store.put(storeKey, new Otp(code, Instant.now().plusSeconds(props.otpTtlSeconds()), 0));

        boolean sent = props.mock();
        if (props.mock()) {
            log.info("OTP mock mode — fixed code, no SMS dispatched");
        } else if (props.enabled()) {
            try {
                String jobId = smsClient.send("91" + number, buildMessage(code, purpose), dltTemplateKey(purpose));
                sent = true;
                log.info("OTP SMS dispatched (jobId={})", jobId);
            } catch (SmsException e) {
                // No PII / code in the log; surfaced to the caller via sent=false.
                log.warn("OTP SMS delivery failed: {}", e.getMessage());
            }
        }
        // Surface the code to the caller in mock or dev-echo mode (never in real prod).
        boolean reveal = props.mock() || props.devEcho();
        return new OtpRequestResult(sent, reveal ? code : null, props.otpTtlSeconds());
    }

    /** Verify (and consume) the OTP for {@code mobile}, scoped to {@code purpose}. */
    @Override
    public boolean verify(String mobile, String code, String purpose) {
        String number = normalize(mobile);
        String storeKey = key(purpose, number);
        // Mock mode: the fixed code always verifies (even if the store entry lapsed).
        if (props.mock() && code != null && mockCode().equals(code.trim())) {
            store.remove(storeKey);
            return true;
        }
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

    /** Build the SMS body from the (DLT-registered) template: {@code {otp}} → code, {@code {ttl}} → minutes. */
    private String buildMessage(String code, String purpose) {
        String template = OtpVerifierPort.BUREAU_CONSENT.equals(purpose)
                        && props.bureauConsentOtpTemplate() != null && !props.bureauConsentOtpTemplate().isBlank()
                ? props.bureauConsentOtpTemplate()
                : props.otpTemplate() != null && !props.otpTemplate().isBlank()
                        ? props.otpTemplate()
                        : "Your OTP for DhanBoost login is {otp}. It is valid for {ttl} minutes. "
                                + "Do not share this OTP with anyone. - DhanBoost";
        return template
                .replace("{otp}", code)
                .replace("{ttl}", String.valueOf(props.otpTtlSeconds() / 60));
    }

    /**
     * DLT Template ID lookup key for {@code purpose} — resolved via {@code navix.sms.dlt-template-ids}
     * in {@link UltronSmsClient#resolveDltTemplateId}, which already falls back to the global
     * {@code navix.sms.dlt-template-id} when the key is unmapped. Until a bureau-consent-specific
     * template is registered with the telco (a differently-worded SMS legally needs its own DLT id),
     * {@code "BUREAU_CONSENT_OTP"} simply won't resolve and the login/global template id is used instead
     * — deliberate, safe fallback (see plan Part F).
     */
    private static String dltTemplateKey(String purpose) {
        return OtpVerifierPort.BUREAU_CONSENT.equals(purpose) ? "BUREAU_CONSENT_OTP" : null;
    }

    private String generate() {
        int bound = (int) Math.pow(10, props.otpLength());
        int min = bound / 10;
        return String.valueOf(min + RANDOM.nextInt(bound - min));
    }

    /** The fixed code accepted in mock mode (default 123456). */
    private String mockCode() {
        return props.mockCode() != null && !props.mockCode().isBlank() ? props.mockCode() : "123456";
    }

    private static String normalize(String mobile) {
        if (mobile == null) {
            throw new BusinessException("INVALID_MOBILE", "Mobile is required");
        }
        String digits = mobile.replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw new BusinessException("INVALID_MOBILE", "Mobile must be a 10-digit number");
        }
        return digits;
    }
}
