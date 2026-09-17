package com.navix.common.verification;

/**
 * The structured, PII-safe diagnostic an outbound provider failure carries — implemented by
 * {@code VerificationException} in {@code navix-verification}.
 *
 * <p><b>Why this lives in navix-common.</b> {@code navix-loan} normalises provider failures into a
 * short error code ({@code ApplicationVerificationService.providerErrorCode}) that is stored on the
 * verification row and surfaced to staff, but {@code navix-loan} depends on {@code navix-common}
 * alone — it cannot see {@code VerificationException}. Without this interface the only thing the
 * loan module can read is {@link Throwable#getMessage()}, which is why that normalisation was a
 * regex over an English sentence. Same seam, same reason as {@link VerificationPort},
 * {@link EsignPort} and {@link ProviderCallContext}.
 *
 * <p><b>Everything here is already redacted.</b> These are the fields {@code ProviderJson} keeps
 * after stripping the response body: PAN, mobile, email and long digit runs are replaced before
 * {@link #safeDetail()} is populated. The unredacted exchange lives only in the
 * {@code provider_api_execution} audit row that the ADMIN workbench reads. Anything added here must
 * hold to that: <b>never widen this interface with a raw response body or a request value.</b>
 *
 * <p>Every accessor may return {@code null} — a transport failure has no HTTP status, and a provider
 * that answers with an unrecognised envelope has no code. Callers must not assume otherwise.
 */
public interface ProviderFailureDetails {

    /**
     * {@link #providerCode()} meaning: the bureau holds records for this identity, but only under
     * mobile numbers we did not send, and releasing them needs a separate vendor endpoint we do not
     * implement. Declared here because {@code navix-verification} raises it and {@code navix-loan}
     * has to recognise it — it is the difference between "this borrower has no credit file" and
     * "their file is one manual step away", and the two were previously indistinguishable.
     */
    String MASKED_MOBILE_REQUIRED = "MASKED_MOBILE_REQUIRED";

    /**
     * {@link #providerCode()} meaning: the PAN does not exist. A definitive vendor ANSWER, not an
     * outage — Signzy's 404 {@code "Pan Number Not Found"}. Raised as a
     * {@code TerminalVerificationException} so the router stops instead of paying two more vendors to
     * repeat it (41 billable Fintrix calls in the Sep-2026 audit did exactly that, and none of the 18
     * traced applications ever got a PAN through afterwards).
     */
    String PAN_NOT_FOUND = "PAN_NOT_FOUND";

    /**
     * {@link #providerCode()} meaning: the bureau has closed the knowledge-based-authentication
     * question — every answer attempt it allowed has been spent (Fintrix {@code S02}). Terminal for
     * that order id: answering again is billable and can only fail.
     */
    String KBA_EXHAUSTED = "KBA_EXHAUSTED";

    /**
     * {@link #providerCode()} meaning: DigiLocker itself is down upstream of our provider (Signzy 409
     * {@code "Upstream Down"}). Retryable, but slowly and with a cap — not at the 5-second cadence a
     * "not ready yet" poll uses, which turned one outage into 183 calls for a single borrower.
     */
    String DIGILOCKER_UPSTREAM_DOWN = "DIGILOCKER_UPSTREAM_DOWN";

    /**
     * {@link #providerCode()} meaning: the borrower declined the DigiLocker consent (Signzy 400
     * {@code AUTH_FAIL}). Terminal for that consent session — polling it cannot change the answer.
     */
    String DIGILOCKER_CONSENT_DENIED = "DIGILOCKER_CONSENT_DENIED";

    /**
     * {@link #providerCode()} meaning: the provider answered, but the body could not be parsed as
     * JSON. Distinct from a transport failure (we did get a response) and from any HTTP code (the
     * status may well have been 200).
     */
    String UNPARSEABLE_RESPONSE = "UNPARSEABLE_RESPONSE";

    /**
     * Every {@link #providerCode()} above: the codes {@code navix-loan} stores verbatim rather than
     * normalising to {@code HTTP_<status>}. Kept here so
     * {@code ApplicationVerificationService.providerErrorCode} needs one membership test instead of a
     * branch per code — add a constant above and it is honoured automatically.
     */
    java.util.Set<String> NAMED_PROVIDER_CODES = java.util.Set.of(
            MASKED_MOBILE_REQUIRED, PAN_NOT_FOUND, KBA_EXHAUSTED,
            DIGILOCKER_UPSTREAM_DOWN, DIGILOCKER_CONSENT_DENIED, UNPARSEABLE_RESPONSE);

    /** HTTP status of the failed call, or {@code null} when it never got one (timeout, DNS, reset). */
    Integer httpStatus();

    /** Provider path the call was made to, e.g. {@code /crif_combine}. Never carries query values. */
    String endpoint();

    /** The provider's own error code, read from an allowlisted set of envelope fields. */
    String providerCode();

    /** A redacted, length-capped fragment of the provider's error message. */
    String safeDetail();
}
