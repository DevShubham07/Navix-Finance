package com.navix.common.verification;

/**
 * Verifies a borrower's mobile OTP. Implemented by the auth module's OTP service and consumed by
 * domain modules that need a step-up check before writing a consent record.
 *
 * <p>This exists as a port for the same reason {@link VerificationPort} does: the module holding
 * the OTP store ({@code navix-app}) sits ABOVE the domain modules in the dependency graph, so
 * {@code navix-loan} cannot inject the service directly.
 *
 * <p>Verification is <b>single-use</b> — a successful call consumes the code, so callers must treat
 * one invocation as one consumed OTP and must not retry it speculatively.
 */
public interface OtpVerifierPort {

    /** The borrower's login OTP. */
    String LOGIN = "LOGIN";
    /** A dedicated OTP purpose for credit-bureau consent — kept isolated from {@link #LOGIN} so a
     *  concurrent login OTP request never clobbers a pending bureau-consent code (or its rate-limit
     *  budget) and vice versa; each purpose gets its own stored code and its own send allowance. */
    String BUREAU_CONSENT = "BUREAU_CONSENT";

    /**
     * @param mobile  the borrower's registered 10-digit mobile — resolve this SERVER-SIDE from stored
     *                profile data, never from the request body, or a caller can verify a code that was
     *                sent to a number they control.
     * @param code    the code the borrower entered
     * @param purpose one of {@link #LOGIN} / {@link #BUREAU_CONSENT} — must match the purpose the code
     *                was requested for, or verification fails.
     * @return true if the code was valid (and is now consumed), false otherwise
     */
    boolean verify(String mobile, String code, String purpose);

    /**
     * Generate + send an OTP for {@code mobile}, scoped to {@code purpose}. Resolve {@code mobile}
     * SERVER-SIDE the same way {@link #verify} requires.
     */
    OtpRequestResult request(String mobile, String purpose);

    /** Result of an OTP send: whether the SMS actually went out, and (dev/mock only) the code itself. */
    record OtpRequestResult(boolean sent, String devCode, int ttlSeconds) {
    }
}
