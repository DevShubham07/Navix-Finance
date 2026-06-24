package com.navix.common.loan;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port for reaching real loans from modules that must not depend on
 * {@code navix-loan} internals (e.g. {@code navix-collections}). Implemented by
 * the loan module ({@code LoanDirectoryAdapter}); the bootable app wires the bean
 * by component scan — the same "swap at a seam" pattern as {@code StaffDirectory}.
 *
 * <p>Reads return a {@link LoanSummary} (loan + borrower snapshot). The single
 * write, {@link #markInCollections(Long)}, is the one mutation collections needs
 * when it opens a case.
 */
public interface LoanDirectory {

    /** The loan + borrower snapshot for {@code loanId}, or empty if no such loan. */
    Optional<LoanSummary> findLoan(Long loanId);

    /**
     * Loans eligible for collections as of {@code asOf}: ACTIVE or OVERDUE with a
     * due date on or before {@code asOf}. Drives the "open a case" picker. Loans
     * already moved to IN_COLLECTIONS are excluded (they have a case).
     */
    List<LoanSummary> listCollectible(LocalDate asOf);

    /**
     * Move a loan into collections: flip ACTIVE/OVERDUE → IN_COLLECTIONS. Idempotent
     * and a no-op for any other status. Called when a collection case is opened.
     */
    void markInCollections(Long loanId);
}
