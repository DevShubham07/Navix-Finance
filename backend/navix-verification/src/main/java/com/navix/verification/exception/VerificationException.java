package com.navix.verification.exception;

import com.navix.common.verification.ProviderFailureDetails;

/**
 * Thrown when an outbound provider call (Signzy/Digitap) fails: a non-2xx HTTP response, a null/empty
 * body, or a provider envelope reporting {@code status == "error"}.
 *
 * <p>Unchecked so the provider chain ({@code RoutingVerificationPort}) and the bureau
 * Experian&rarr;CRIF fallback can fall through on a single {@code catch (VerificationException)}.
 * Its subclass {@link CapabilityNotSupportedException} distinguishes "provider doesn't offer this"
 * from "provider tried and failed".
 *
 * <p>Implements {@link ProviderFailureDetails} so {@code navix-loan} — which depends on
 * {@code navix-common} only and cannot see this class — can read the structured diagnostic
 * instead of pattern-matching the message text. See that interface for why.
 */
public class VerificationException extends RuntimeException implements ProviderFailureDetails {

    private final Integer httpStatus;
    private final String endpoint;
    private final String providerCode;
    private final String safeDetail;

    public VerificationException(String message) {
        super(message);
        this.httpStatus = null;
        this.endpoint = null;
        this.providerCode = null;
        this.safeDetail = null;
    }

    public VerificationException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = null;
        this.endpoint = null;
        this.providerCode = null;
        this.safeDetail = null;
    }

    public VerificationException(String message, Throwable cause, Integer httpStatus,
                                 String endpoint, String providerCode, String safeDetail) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.endpoint = endpoint;
        this.providerCode = providerCode;
        this.safeDetail = safeDetail;
    }

    @Override
    public Integer httpStatus() {
        return httpStatus;
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    @Override
    public String providerCode() {
        return providerCode;
    }

    @Override
    public String safeDetail() {
        return safeDetail;
    }
}
