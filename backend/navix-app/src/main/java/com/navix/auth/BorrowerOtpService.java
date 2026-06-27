package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.sms.SmsException;
import com.navix.sms.SmsProperties;
import com.navix.sms.UltronSmsClient;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Borrower mobile-OTP: generates a random code, delivers it via the {@link UltronSmsClient}
 * SMS gateway, and verifies it on login. Replaces the old fixed-{@code 123456} mock.
 *
 * <p>Codes are held in-memory (single-instance demo; move to Redis/DB for HA), keyed by the
 * normalized 10-digit mobile, with a TTL and a small attempt cap. The OTP is never logged or
 * returned — except when {@code navix.sms.dev-echo=true} (local testing without a handset).
 */
@Service
@RequiredArgsConstructor
public class BorrowerOtpService {

    private static final Logger log = LoggerFactory.getLogger(BorrowerOtpService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UltronSmsClient smsClient;
    private final SmsProperties props;
    private final Map<String, Otp> store = new ConcurrentHashMap<>();

    private record Otp(String code, Instant expiresAt, int attempts) {
    }

    /** Result of an OTP request: whether the SMS went out, and (dev-echo only) the code. */
    public record OtpRequest(boolean sent, String devCode, int ttlSeconds) {
    }

    /** Generate + store an OTP for {@code mobile} and send it via SMS. */
    public OtpRequest request(String mobile) {
        String number = normalize(mobile);
        String code = generate();
        store.put(number, new Otp(code, Instant.now().plusSeconds(props.otpTtlSeconds()), 0));

        boolean sent = false;
        if (props.enabled()) {
            try {
                String jobId = smsClient.send("91" + number,
                        "Your NAVIX verification code is " + code + ". Valid for "
                                + (props.otpTtlSeconds() / 60) + " minutes. Do not share it.");
                sent = true;
                log.info("OTP SMS dispatched (jobId={})", jobId);
            } catch (SmsException e) {
                // No PII / code in the log; surfaced to the caller via sent=false.
                log.warn("OTP SMS delivery failed: {}", e.getMessage());
            }
        }
        return new OtpRequest(sent, props.devEcho() ? code : null, props.otpTtlSeconds());
    }

    /** Verify (and consume) the OTP for {@code mobile}. */
    public boolean verify(String mobile, String code) {
        String number = normalize(mobile);
        Otp otp = store.get(number);
        if (otp == null || code == null) {
            return false;
        }
        if (Instant.now().isAfter(otp.expiresAt()) || otp.attempts() >= MAX_ATTEMPTS) {
            store.remove(number);
            return false;
        }
        if (!otp.code().equals(code.trim())) {
            store.put(number, new Otp(otp.code(), otp.expiresAt(), otp.attempts() + 1));
            return false;
        }
        store.remove(number); // single-use
        return true;
    }

    private String generate() {
        int bound = (int) Math.pow(10, props.otpLength());
        int min = bound / 10;
        return String.valueOf(min + RANDOM.nextInt(bound - min));
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
