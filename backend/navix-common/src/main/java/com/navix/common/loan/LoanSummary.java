package com.navix.common.loan;

import java.time.LocalDate;

/**
 * Read-only snapshot of a real loan plus its borrower, exposed across the
 * {@link LoanDirectory} port so modules that don't depend on {@code navix-loan}
 * (e.g. {@code navix-collections}) can show loan + borrower detail.
 *
 * <p>All monetary fields are <b>integer paise</b>. {@code status} is the loan
 * status name (kept as a {@code String} so this record stays free of the loan
 * enum). Borrower fields are sourced from the customer KYC snapshot; the PAN is
 * already masked. Any borrower field may be {@code null} when no profile exists.
 *
 * <p>{@code preDue} is true while the due date is still in the future: collections can work such a
 * loan (a courtesy call before salary day), but it is not a delinquency and must never be presented
 * or counted as one.
 */
public record LoanSummary(
        Long loanId,
        Long customerId,
        Long applicationId,
        String status,
        Long principalPaise,
        Long netDisbursedPaise,
        Long totalRepayablePaise,
        Long outstandingPaise,
        LocalDate disbursedOn,
        LocalDate dueDate,
        String borrowerName,
        String panMasked,
        String employer,
        String employmentStatus,
        Long monthlySalaryPaise,
        String salaryBank,
        boolean preDue) {
}
