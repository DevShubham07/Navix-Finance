package com.navix.common.loan;

import java.time.LocalDate;

/**
 * Port for presenting a NACH debit from modules that must not depend on {@code navix-loan}
 * (collections). Implemented by {@code DebitDirectoryAdapter}; same seam as {@link LoanDirectory}.
 * The implementation owns every money guard; callers own authorisation and the feature flag.
 */
public interface DebitDirectory {

    /**
     * Manually present one debit for {@code loanId} on behalf of staff {@code actorId}.
     * {@code amountPaise == null} means the full outstanding as of today.
     * Refusals surface as {@code BusinessException} with a stable code.
     */
    Result presentForCase(Long loanId, Long amountPaise, Long actorId);

    /** Every presentation made on one loan, newest attempt first. Read-only; works with the flag off. */
    java.util.List<DebitRecord> history(Long loanId);

    /**
     * Debits that took money a borrower no longer owed, newest first — the refund register. Read-only.
     */
    java.util.List<DebitRecord> overpayments();

    /** Re-read one in-flight debit's outcome from the provider. Safe to repeat. */
    DebitRecord reconcile(Long loanId, Long debitId);

    /** Cancel one in-flight debit at the provider (only possible before the bank picks it up). */
    DebitRecord cancel(Long loanId, Long debitId, String actorLabel);

    /** What a caller may see: no provider message, no failure code. */
    record Result(Long debitId, String status, long amountPaise, LocalDate settlementDate, int attemptNo) {
    }

    /**
     * One presentation as staff see it. The provider's failure code and message are deliberately absent
     * (the message echoes account numbers); {@code representable} is what staff actually need from them —
     * whether another attempt is possible.
     */
    record DebitRecord(Long debitId, Long loanId, String status, long amountPaise, LocalDate settlementDate,
                       int attemptNo, String trigger, String utr, boolean overpayment, boolean representable,
                       java.time.Instant createdAt) {
    }
}
