package com.navix.verification.exception;

/**
 * Thrown when an outbound provider call (Signzy/Digitap) fails: a non-2xx HTTP response, a null/empty
 * body, or a provider envelope reporting {@code status == "error"}.
 *
 * <p>Unchecked so the provider chain ({@code RoutingVerificationPort}) and the bureau
 * Experian&rarr;CRIF fallback can fall through on a single {@code catch (VerificationException)}.
 * Its subclass {@link CapabilityNotSupportedException} distinguishes "provider doesn't offer this"
 * from "provider tried and failed".
 */
public class VerificationException extends RuntimeException {

    public VerificationException(String message) {
        super(message);
    }

    public VerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
