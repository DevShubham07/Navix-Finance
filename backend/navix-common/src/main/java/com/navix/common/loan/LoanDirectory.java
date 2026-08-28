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
     * The collections worklist as of {@code asOf}: every live loan (ACTIVE / OVERDUE /
     * IN_COLLECTIONS) already past due, <b>plus</b> those falling due within the next
     * {@link #PRE_DUE_WINDOW_DAYS} days so an officer can chase the borrower before salary day.
     * Sorted by due date, soonest first.
     *
     * <p>Loans further out are excluded on purpose — without a window this returns the entire live
     * book from the day of disbursal and the UPCOMING bucket stops meaning anything.
     */
    List<LoanSummary> listCollectible(LocalDate asOf);

    /** How far ahead of the due date a loan becomes workable by collections. */
    int PRE_DUE_WINDOW_DAYS = 7;

    /**
     * Live loans whose due date is still AHEAD of {@code asOf}, soonest first — the pre-due
     * watchlist behind the collections UPCOMING bucket. The counterpart to
     * {@link #listCollectible(LocalDate)}, which covers loans already due.
     *
     * <p>Strictly read-only: nothing here opens a case or flips a loan into IN_COLLECTIONS.
     *
     * <p>Implementations MUST batch: because every advance runs to at most
     * {@code LoanMath.MAX_TERM_DAYS}, "not yet due" is effectively the whole live book, so this
     * has to cost a fixed number of queries rather than a handful per row.
     */
    List<LoanSummary> listUpcoming(LocalDate asOf);

    /**
     * Move a loan into collections: flip ACTIVE/OVERDUE → IN_COLLECTIONS <b>once it is actually past
     * due</b>. Idempotent, and a no-op for any other status — and, deliberately, for a loan that has
     * not reached its due date: a pre-emptive case is a courtesy call, and flipping the status would
     * report a borrower who has done nothing wrong as delinquent across every queue, segment and
     * dashboard count.
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
