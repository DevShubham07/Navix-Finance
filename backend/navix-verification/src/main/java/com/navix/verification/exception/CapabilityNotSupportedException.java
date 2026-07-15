package com.navix.verification.exception;

/**
 * Thrown by a provider adapter when the provider does <b>not offer</b> a given verification
 * capability at all (as opposed to a call that was attempted and failed, which surfaces as a plain
 * {@link VerificationException}).
 *
 * <p>The routing layer ({@code RoutingVerificationPort}) uses this distinction: a
 * {@code CapabilityNotSupportedException} means "skip this provider and try the next in the chain",
 * whereas a {@link VerificationException} means "this provider tried and failed — fall through to the
 * next as a fallback". Examples: Signzy has no email/address APIs; Digitap has no penny-drop or
 * DigiLocker consent flow.
 */
public class CapabilityNotSupportedException extends VerificationException {

    public CapabilityNotSupportedException(String message) {
        super(message);
    }
}
