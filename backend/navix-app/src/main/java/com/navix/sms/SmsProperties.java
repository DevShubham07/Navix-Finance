package com.navix.sms;

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
        String dltTemplateId,
        boolean enabled,
        boolean devEcho,
        int otpTtlSeconds,
        int otpLength
) {
    public boolean usesApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
