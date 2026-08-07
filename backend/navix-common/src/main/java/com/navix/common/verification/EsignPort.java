package com.navix.common.verification;

import java.time.Instant;

/**
 * Port for electronic signature of the sanction letter / Key Fact Statement (Phase 3).
 *
 * <p>Deliberately a seam rather than an inline mock: the real vendor flow (Aadhaar eSign / NeSL) is a
 * redirect-and-callback like DigiLocker, so the interface is shaped for that — {@link #initiate}
 * mints a session for a stored document and {@link #fetch} resolves it — even though
 * {@code MockEsignAdapter} answers both immediately. Swapping in the real provider then means adding
 * one adapter, not rewriting {@code ApplicationVerificationService}.
 *
 * <p>Same provider-neutral rule as {@link VerificationPort}: no vendor DTO crosses onto the loan
 * classpath.
 */
public interface EsignPort {

    /**
     * Start a signing session for a document already stored at {@code documentUrl} (a presigned GET).
     * {@code signerName} / {@code signerMobile} identify the signatory to the provider.
     */
    EsignSession initiate(String documentUrl, String signerName, String signerMobile, String clientRef);

    /** Resolve a session. {@code completed=false} while the signer is still with the provider. */
    EsignResult fetch(String sessionId);

    /**
     * @param sessionId provider handle for this signing
     * @param signUrl   where to send the borrower to sign; null when the provider signs inline
     * @param provider  provider name for the audit row
     */
    record EsignSession(String sessionId, String signUrl, String provider) {
    }

    /**
     * @param completed    the signer has finished (successfully or not)
     * @param signed       a signature was actually captured
     * @param signedAt     when the provider recorded the signature
     * @param signedPdf    the signed document bytes, when the provider returns them; may be null
     * @param signatureRef the provider's signature/transaction reference for the audit row
     * @param provider     provider name
     * @param reason       why an incomplete/unsigned result came back; null on success
     */
    record EsignResult(boolean completed, boolean signed, Instant signedAt, byte[] signedPdf,
                       String signatureRef, String provider, String reason) {
    }
}
