package com.navix.verification.exception;

/**
 * Thrown when a provider gave a <b>definitive answer</b> about this input — "no such PAN", "the
 * borrower declined consent", "the bureau closed this question" — rather than failing to serve the
 * request.
 *
 * <p>{@code RoutingVerificationPort} catches this <i>before</i> the plain {@link
 * VerificationException} and rethrows it immediately: falling through would ask the next provider to
 * repeat a question that has already been answered, which costs money and cannot change the outcome.
 * In the Sep-2026 provider audit that distinction accounted for 41 billable Fintrix PAN calls on PANs
 * Signzy had already reported as nonexistent.
 *
 * <p>The specific verdict travels in {@link #providerCode()} — see the constants on
 * {@code ProviderFailureDetails} ({@code PAN_NOT_FOUND}, {@code DIGILOCKER_CONSENT_DENIED},
 * {@code DIGILOCKER_UPSTREAM_DOWN}, {@code KBA_EXHAUSTED}) — so {@code navix-loan}, which cannot see
 * this class, can still tell the cases apart. One class rather than a subclass per verdict: the
 * router's only question is "stop or fall through", and every caller that needs more reads the code.
 *
 * <p>Deliberately NOT used for a vendor outage, a timeout, or an unparseable body. Those are
 * {@link VerificationException}s and must keep falling through — that fall-through rescued 15 of 15
 * bureau pulls during the Fintrix balance outage and 14 of 18 during Digitap's.
 */
public class TerminalVerificationException extends VerificationException {

    public TerminalVerificationException(String message, Throwable cause, Integer httpStatus,
                                         String endpoint, String providerCode, String safeDetail) {
        super(message, cause, httpStatus, endpoint, providerCode, safeDetail);
    }
}
