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
    /** An admin correcting a customer's mobile number on file — the code is sent to the NEW number,
     *  proving it is real and reachable before it is saved over the old one. */
    String ADMIN_MOBILE_CHANGE = "ADMIN_MOBILE_CHANGE";
    /** An admin correcting a customer's approved (sanctioned) loan amount — the code is sent to the
     *  customer's EXISTING number on file, as the borrower's own consent that the figure credit
     *  approved for them is changing. */
    String ADMIN_AMOUNT_CHANGE = "ADMIN_AMOUNT_CHANGE";

    /**
     * @param mobile  the number the code was sent to. For every purpose except
     *                {@link #ADMIN_MOBILE_CHANGE}, resolve this SERVER-SIDE from stored profile data,
     *                never from the request body, or a caller can verify a code that was sent to a
     *                number they control. {@link #ADMIN_MOBILE_CHANGE} is the one deliberate
     *                exception — the whole point is to verify a NEW number that has nothing stored
     *                yet, so it legitimately comes from the request; callers MUST reuse the exact
     *                same string across the request and confirm calls rather than re-deriving it.
     * @param code    the code the borrower entered
     * @param purpose one of {@link #LOGIN} / {@link #BUREAU_CONSENT} / {@link #ADMIN_MOBILE_CHANGE} /
     *                {@link #ADMIN_AMOUNT_CHANGE} — must match the purpose the code was requested
     *                for, or verification fails.
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
