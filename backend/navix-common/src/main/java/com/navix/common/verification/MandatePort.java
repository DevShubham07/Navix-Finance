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
