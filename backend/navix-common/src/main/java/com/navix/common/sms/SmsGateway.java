package com.navix.common.sms;

/**
 * Minimal outbound-SMS port, shaped to match the existing {@code UltronSmsClient.send(number, text)}.
 * Implemented in navix-app (the UltronSMS client) and consumed by the notification engine's
 * {@code SmsSender}. Returns a provider message/job reference; may throw on transport failure —
 * the {@code SmsSender} isolates that so one failed send never aborts the rest of a fan-out.
 */
public interface SmsGateway {

    /**
     * Send {@code text} to {@code number} (E.164-ish, e.g. {@code "919876543210"}). In SMS mock mode
     * the implementation short-circuits and returns a mock reference without hitting the gateway.
     */
    String send(String number, String text);
}
