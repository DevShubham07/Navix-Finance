package com.navix.common.verification;

/**
 * Verifies a borrower's email addresses by OTP — sibling of {@link OtpVerifierPort} (mobile/SMS-
 * shaped), same purpose-scoped, single-use, rate-limited contract, delivered by email instead of SMS.
 *
 * <p>This exists as a port for the same reason {@link OtpVerifierPort} does: the module holding the
 * OTP store and the email transport ({@code navix-app} / {@code navix-notification}) sits ABOVE the
 * domain modules, so {@code navix-loan} cannot inject the service directly.
 *
 * <p>Deliberately SEPARATE from the Signzy/Digitap deliverability + employer-match check on the
 * OFFICIAL email ({@code VerificationPort.verifyEmail}), which this does not touch. Both now run
 * against that same address, and they answer different questions: the provider check corroborates
 * the EMPLOYER, an OTP proves the borrower can OPEN the inbox. Neither substitutes for the other.
 */
public interface EmailOtpPort {

    /** The borrower's personal-email ownership proof taken during onboarding. */
    String PERSONAL_EMAIL = "PERSONAL_EMAIL";

    /**
     * The borrower's official/work-email ownership proof taken during onboarding. Codes are keyed on
     * purpose + address, so this and {@link #PERSONAL_EMAIL} stay independent even when a borrower
     * types the same address into both fields.
     */
    String OFFICIAL_EMAIL = "OFFICIAL_EMAIL";

    /**
     * @param email   the address being verified — resolve this SERVER-SIDE from stored profile
     *                data, never from the request body, or a caller can verify a code that was sent
     *                to an inbox they don't control.
     * @param code    the code the borrower entered
     * @param purpose must match the purpose the code was requested for, or verification fails.
     * @return true if the code was valid (and is now consumed), false otherwise
     */
    boolean verify(String email, String code, String purpose);

    /**
     * Generate + send an OTP for {@code email}, scoped to {@code purpose}. Resolve {@code email}
     * SERVER-SIDE the same way {@link #verify} requires.
     */
    OtpVerifierPort.OtpRequestResult request(String email, String purpose);
}
