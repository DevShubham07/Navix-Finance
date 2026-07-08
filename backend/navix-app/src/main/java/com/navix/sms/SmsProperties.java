package com.navix.sms;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code navix.sms.*} — the UltronSMS gateway used for borrower OTP delivery.
 *
 * <p>Auth is either {@code apiKey} (preferred) or {@code user}/{@code password}. The doc
 * demo creds are placeholders (return "username or password is invalid"); set real creds
 * via {@code NAVIX_SMS_*} env / SSM. {@code devEcho} (default false) returns the OTP in the
 * request response for local testing without a handset — keep it OFF in production.
 */
@ConfigurationProperties(prefix = "navix.sms")
public record SmsProperties(
        String baseUrl,
        String user,
        String password,
        String apiKey,
        String senderId,
        String channel,
        String route,
        String peid,
        /** Fallback / OTP DLT Template ID, used when {@link #dltTemplateIds} has no entry for a type. */
        String dltTemplateId,
        /** Per-{@code NotificationType} DLT Template IDs, keyed by the enum name (e.g.
         *  {@code LOAN_DISBURSED}). Lifecycle SMS resolve their id here first, then fall back to
         *  {@link #dltTemplateId}. {@code CREDIT_REJECTED} and {@code REBORROW_REVIEW_REJECTED} share
         *  the single approved {@code NAVIX_APPLICATION_DECLINED} id. May be null/empty (all fall back). */
        Map<String, String> dltTemplateIds,
        /** DLT-registered OTP message; {@code {otp}} → the code, {@code {ttl}} → minutes. Must
         *  match a template registered for the sender, or the gateway returns "Invalid template text". */
        String otpTemplate,
        boolean enabled,
        boolean devEcho,
        int otpTtlSeconds,
        int otpLength,
        /** Demo/testing OTP mock: a fixed {@code mockCode} always verifies, no SMS sent. OFF in prod. */
        boolean mock,
        String mockCode
) {
    public boolean usesApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
