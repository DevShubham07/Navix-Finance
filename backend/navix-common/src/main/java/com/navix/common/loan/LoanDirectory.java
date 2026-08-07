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
 * <p>Reads return a {@link LoanSummary} (loan + borrower snapshot). Two writes cross
 * the seam: {@link #markInCollections(Long)} when a case is opened, and
 * {@link #creditCollectionPayment} when the Accountant validates a payment
 * collections took (V47).
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

    /**
     * Post a collections-side payment to the loan ledger as an already-verified repayment, and
     * return its {@code payment.id}. Called only when the Accountant validates a
     * {@code collection_payment} — collections itself never moves the balance, which is the whole
     * point of routing every collected rupee past a checker (revamp.md decision 44).
     *
     * <p>Recorded verified in one step rather than PENDING_VERIFICATION: the validation <em>is</em>
     * the verification, and leaving it pending would put the same payment in the Accountant's
     * repayment queue a second time.
     *
     * @param loanId      the loan being credited
     * @param amountPaise the amount collected, in paise
     * @param txnRef      the bank/UPI reference, if collections captured one
     * @param paidOn      the date the borrower actually paid (not the validation date)
     * @return the id of the ledger payment created
     */
    Long creditCollectionPayment(Long loanId, long amountPaise, String txnRef,
                                 String proofRef, LocalDate paidOn);
}
