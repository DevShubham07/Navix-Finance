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

    /**
     * @param mobile the borrower's registered 10-digit mobile — resolve this SERVER-SIDE from stored
     *               profile data, never from the request body, or a caller can verify a code that was
     *               sent to a number they control.
     * @param code   the code the borrower entered
     * @return true if the code was valid (and is now consumed), false otherwise
     */
    boolean verify(String mobile, String code);
}
