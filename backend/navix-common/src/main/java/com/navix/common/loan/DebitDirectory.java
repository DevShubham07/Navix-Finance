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

    /** What a caller may see: no provider message, no failure code. */
    record Result(Long debitId, String status, long amountPaise, LocalDate settlementDate, int attemptNo) {
    }
}
