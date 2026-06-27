package com.navix.auth;

import jakarta.validation.constraints.NotBlank;

/** Request/response records for the real auth endpoints (P6). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record StaffLoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** Borrower login: mobile + the OTP delivered by SMS (UltronSMS gateway). */
    public record BorrowerLoginRequest(@NotBlank String mobile, @NotBlank String otp, String name, Long applicantId) {
    }

    /** Request an OTP be generated + SMS-delivered to {@code mobile}. */
    public record OtpRequestRequest(@NotBlank String mobile) {
    }

    /** OTP-request outcome: whether the SMS went out + ttl. {@code devCode} is set only when
     *  {@code navix.sms.dev-echo=true} (local testing without a handset). */
    public record OtpRequestResponse(boolean sent, int ttlSeconds, String devCode) {
    }

    /** Issued token + the identity it carries (the BFF stores the token in an httpOnly cookie). */
    public record AuthResponse(String token, String id, String name, String role, Long applicantId) {
    }
}
