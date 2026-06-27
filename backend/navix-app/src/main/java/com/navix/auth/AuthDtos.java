package com.navix.auth;

import jakarta.validation.constraints.NotBlank;

/** Request/response records for the real auth endpoints (P6). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record StaffLoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** Borrower login: mobile + OTP. OTP delivery stays mocked (fixed code) per decision 3. */
    public record BorrowerLoginRequest(@NotBlank String mobile, @NotBlank String otp, String name, Long applicantId) {
    }

    /** Issued token + the identity it carries (the BFF stores the token in an httpOnly cookie). */
    public record AuthResponse(String token, String id, String name, String role, Long applicantId) {
    }
}
