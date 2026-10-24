package com.navix.common.loan;

/**
 * The itemised dues on one loan plus the borrower details a collections register shows beside them —
 * what {@link LoanSummary} leaves out. Built from the same outstanding formula the repay page uses
 * ({@code RepaymentService.breakdown}), so nothing here can disagree with "amount owed".
 *
 * <p>All money is integer paise. {@code grossDuePaise} = principal + accrued interest + late penalty,
 * <i>before</i> any payment or settlement cap; {@code paidPaise} is the sum of VERIFIED payments;
 * {@code settledAmountPaise} is the approved full-and-final amount, or null.
 */
public record LoanDues(
        Long loanId,
        long interestPaise,
        int interestDays,
        long penaltyPaise,
        int penaltyDays,
        long paidPaise,
        long grossDuePaise,
        Long settledAmountPaise,
        String email,
        String accountNumber,
        String ifsc,
        Long bureauScore) {
}
