package com.navix.common.verification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port for registering a NACH eMandate (the borrower's standing authority to debit their account on
 * repayment day). Same shape and rule as {@link EsignPort}: one provider, redirect-and-return, and no
 * vendor DTO crosses onto the loan classpath.
 *
 * <p>{@link #register} files a <b>real NPCI mandate request</b> and is not retry-safe — a repeat creates
 * a second mandate. After an unknown outcome (timeout) recover with {@link #findByRef}, never re-register.
 */
public interface MandatePort {

    /** File a mandate request and mint the hosted authorisation session the borrower is sent to. */
    MandateSession register(MandateRequest request);

    /** Read a mandate by the provider's id. The provider's record, not a webhook, is the source of truth. */
    MandateResult fetch(String digioMandateId);

    /** Look a mandate up by our {@code customerRefNumber}; empty when none was ever filed under it. */
    Optional<MandateResult> findByRef(String customerRefNumber);

    /** Banks that can do API mandate, with the authorisation methods each allows. Consult per borrower. */
    List<BankCapability> liveBanks();

    /**
     * Cancel a registered mandate at the provider. {@code reason} is an NPCI code — {@code C001}
     * customer request, {@code C002} corporate request, {@code C003} account closed/frozen,
     * {@code C004} cancelled bank-side. Returns false when the provider refuses; which states permit
     * cancellation is undocumented (docs/digio/11 Q9), so a refusal is a normal outcome, not a bug.
     */
    boolean cancelMandate(String digioMandateId, String umrn, String destIfsc, String reason);

    /**
     * Schedule one NACH debit against a registered mandate. Moves <b>real money</b> and is not retry-safe:
     * after an unknown outcome (timeout) do not re-present; the {@code uniqueKey} is the only handle.
     */
    DebitPlan presentDebit(DebitRequest request);

    /**
     * Recover a debit by our {@code uniqueKey}; empty only when Digio has no plan under it (nothing was
     * created), any other failure throws. Use this, never a re-present, after a timed-out
     * {@link #presentDebit}: a blind retry debits the borrower twice. Also where {@code txnId} is resolved.
     */
    Optional<DebitPlan> findDebitByRef(String uniqueKey);

    /** Read a debit transaction by the provider's {@code NPT...} id. Read-only and retry-safe. */
    DebitOutcome debitStatus(String txnId);

    /** Cancel a debit; {@code false} when the provider refuses (it is only allowed before initiation). */
    boolean cancelDebit(String txnId);

    /**
     * @param amountPaise     integer paise, sent as Digio's {@code amount_in_paise}
     * @param uniqueKey       at most 30 of {@code [A-Za-z0-9-_]}; the recovery handle for a timed-out create
     * @param narration       at most 20 chars; what the borrower reads on their bank statement
     */
    record DebitRequest(String umrn, long amountPaise, LocalDate settlementDate, String uniqueKey,
                        String corporateAccountNumber, String narration) {
    }

    /**
     * @param txnId the {@code NPT...} id. <b>May be null</b>: Digio's create response is not documented to carry
     *              one. It is never substituted with the plan id ({@code NSP...}), which {@link #debitStatus}
     *              would reject; resolve it later from the plan read-back.
     */
    record DebitPlan(String planId, String status, String txnId, LocalDate scheduledSettlementDate) {
    }

    record DebitOutcome(String txnId, String status, String utr, String failureCode, String failureReason,
                        long amountPaise) {
    }

    /**
     * @param customerRefNumber    our unique reconciliation key (at most 20 alphanumerics)
     * @param maximumAmountPaise   per-instruction ceiling in integer paise; converted to rupees once, in the adapter
     * @param finalCollectionDate  nullable; the provider defaults it to 40 years out
     */
    record MandateRequest(String customerRefNumber, String customerName, String mobile,
                          String accountNumber, String ifsc, String bankName,
                          long maximumAmountPaise, LocalDate firstCollectionDate,
                          LocalDate finalCollectionDate) {
    }

    /** @param authenticationUrl where to send the borrower to authorise; a bearer-style link, never log it */
    record MandateSession(String digioMandateId, String state, String authenticationUrl) {
    }

    /**
     * @param state         provider state, e.g. {@code partial} until the bank accepts
     * @param umrn          null until the bank has registered the mandate
     * @param authenticatedAt nullable
     */
    record MandateResult(String digioMandateId, String state, String bankState, String umrn,
                         String authSubMode, String rejectCode, String rejectReason,
                         Instant authenticatedAt) {
    }

    record BankCapability(String ifscPrefix, boolean apiMandate, List<String> allowedAuthSubTypes) {
    }
}
