package com.navix.common.verification;

import java.time.Instant;
import java.util.List;

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
     * {@link EsignResult#reason} value meaning the provider no longer knows this session — it lapsed
     * without a signature. Distinct from an error: our sanctions outlive the provider's sessions, so the
     * caller mints a fresh one rather than failing the borrower.
     */
    String CONTRACT_EXPIRED = "CONTRACT_EXPIRED";

    /** Start a signing session for the document described by {@code request}. */
    EsignSession initiate(EsignRequest request);

    /** Resolve a session. {@code completed=false} while the signer is still with the provider. */
    EsignResult fetch(String sessionId);

    /**
     * Admin "eSign documents": open ONE contract with several signers, each with their own pages and
     * position. Separate from {@link #initiate} (the live borrower path, one signer, all pages,
     * bottom-left). Every call is a real, billable, legally binding contract.
     */
    ContractSession initiateContract(ContractRequest request);

    /** Resolve a multi-signer contract. Never throws for an expired contract, see {@link ContractStatus#expired}. */
    ContractStatus fetchContract(String contractId);

    /**
     * @param signers one entry per signatory; the returned {@link ContractSession#signers} is in the same order
     */
    record ContractRequest(String documentUrl, String contractName, String executerName,
                           String successRedirectUrl, String failureRedirectUrl,
                           List<ContractSignerSpec> signers) {
    }

    /**
     * @param pages    {@code ["All"]} or explicit 1-based page numbers as strings
     * @param position one of TopLeft, TopCenter, TopRight, MiddleLeft, MiddleCenter, MiddleRight,
     *                 BottomLeft, BottomCenter, BottomRight
     */
    record ContractSignerSpec(String name, List<String> pages, String position) {
    }

    record ContractSession(String contractId, List<ContractSignerLink> signers) {
    }

    record ContractSignerLink(String signerId, String signUrl) {
    }

    /**
     * @param expired       the provider no longer knows the contract (lapsed unsigned)
     * @param completed     every signer has signed and the final PDF exists
     * @param signers       per-signer state in the provider's order
     * @param signedPdf     the final signed bytes; only set when {@code completed}
     */
    record ContractStatus(boolean expired, boolean completed, List<ContractSignerStatus> signers,
                          byte[] signedPdf, String auditCertificateUrl) {
    }

    /** @param status normalised: PENDING | SIGNED | FAILED */
    record ContractSignerStatus(String signerId, String status, String error, String nameMatch) {
    }

    /**
     * @param documentUrl        the document to sign, already stored (a presigned GET)
     * @param signer             who is signing
     * @param clientRef          our correlation id for the provider's audit trail
     * @param successRedirectUrl where the provider returns the signer after a successful journey
     * @param failureRedirectUrl where the provider returns the signer after a failed one
     * @param contractName       human label for the document, shown in the provider's UI
     * @param executerName       the entity initiating the signing (the lender)
     */
    record EsignRequest(String documentUrl, Signer signer, String clientRef,
                        String successRedirectUrl, String failureRedirectUrl,
                        String contractName, String executerName) {
    }

    /**
     * The signatory. Everything past {@code mobile} comes from the borrower's Aadhaar and is
     * <em>nullable</em>: DigiLocker is deliberately non-blocking, so a borrower can reach the signature
     * without it. A provider that can match the signing identity against these should degrade to
     * matching on {@code name} alone rather than refuse the signature.
     */
    record Signer(String name, String mobile, String gender, String yearOfBirth,
                  String uidLastFourDigits) {
    }

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
