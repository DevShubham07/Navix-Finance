package com.navix.verification.exception;

/**
 * Thrown when an outbound Fintrix/DigiLocker call fails: a non-2xx HTTP response, a null/empty
 * body, or a provider envelope reporting {@code status == "error"}.
 *
 * <p>Unchecked so the bureau primary&rarr;fallback orchestration ({@code BureauService}) can keep
 * using a single {@code catch (RuntimeException)}.
 */
public class VerificationException extends RuntimeException {

    public VerificationException(String message) {
        super(message);
    }

    public VerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
