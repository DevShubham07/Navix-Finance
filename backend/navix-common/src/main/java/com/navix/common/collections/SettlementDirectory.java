package com.navix.common.collections;

import java.util.Optional;

/**
 * Port for reading an <b>approved</b> collections settlement from modules that must not depend on
 * {@code navix-collections} internals (notably {@code navix-loan}'s repayment/outstanding logic).
 * Implemented by the collections module ({@code SettlementDirectoryAdapter}); the bootable app wires
 * the bean by component scan — the mirror of {@link com.navix.common.loan.LoanDirectory} (which runs
 * the other way: collections reading loans).
 *
 * <p>This is the seam that turns a maker-checker settlement into a real concession: once a Collection
 * Head approves a partial settlement, the agreed full-and-final amount becomes the borrower's payable
 * and the loan closes when it is paid. All amounts are <b>integer paise</b>.
 */
public interface SettlementDirectory {

    /**
     * The operative approved settlement amount (paise) for a loan, if one exists — i.e. the agreed
     * full-and-final figure the borrower must pay to close the loan. Returns empty when the loan has
     * no collection case or no approved settlement (the normal principal + interest + penalty balance
     * then applies). When several settlements were approved on the case, the most recently approved
     * one is operative.
     *
     * @param loanId the real (bigint) loan id
     * @return the approved settlement amount in paise, or empty if none applies
     */
    Optional<Long> approvedSettlementAmount(Long loanId);
}
