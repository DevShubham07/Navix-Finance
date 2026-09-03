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

    /** HTTP status of the failed call, or {@code null} when it never got one (timeout, DNS, reset). */
    Integer httpStatus();

    /** Provider path the call was made to, e.g. {@code /crif_combine}. Never carries query values. */
    String endpoint();

    /** The provider's own error code, read from an allowlisted set of envelope fields. */
    String providerCode();

    /** A redacted, length-capped fragment of the provider's error message. */
    String safeDetail();
}
