package com.navix.loan.dto;

import java.time.LocalDate;

/**
 * DTOs for the staff <b>loan register</b> — every loan the platform has ever disbursed, any
 * status, in one searchable/date-ranged read (unlike the {@code /api/loan} ledger, which is
 * per-loan, and the accountant's {@code TransactionService} ledger, which is per money-movement
 * event). See {@code LoanRegisterService} for the batched enrichment this is built from.
 */
public final class LoanRegisterDtos {

    private LoanRegisterDtos() {
        // DTO holder — no instances.
    }

    /**
     * One row of the register. All money is <b>integer paise</b>; every date is a plain
     * {@link LocalDate} (never a raw day-count — "due in N days" is a UI rule, not a wire shape).
     *
     * @param loanId                the real (bigint) loan id
     * @param customerId            the borrower's customer id
     * @param applicationId         the {@code loan_application} that minted this loan, if resolvable
     * @param borrowerName          from the customer's KYC profile; null if unresolved
     * @param mobile                from the customer's KYC profile; null if unresolved
     * @param panMasked             the customer's PAN, masked (never the raw value — see {@link
     *                              com.navix.common.util.Masking#maskPan})
     * @param loanCycle             1-based index of this loan among the customer's loans, ordered by
     *                              {@code disbursedOn} (tie-break loan id) — "this is their 3rd advance"
     * @param principalPaise        sanctioned principal
     * @param netDisbursedPaise     what the borrower actually received (principal − fee − GST)
     * @param totalRepayablePaise   principal + scheduled interest (fee/GST excluded — taken up front)
     * @param outstandingPaise      the authoritative, penalty/prepayment-aware balance today (from
     *                              {@code RepaymentService#outstandingForAll}) — agrees with the repay
     *                              page and collections by construction
     * @param sanctionedAmountPaise the amount the credit desk sanctioned (may differ from the
     *                              borrower's eventual {@code amountRequested}/principal)
     * @param sanctionedAt          the date credit sanctioned the application, in IST; null if never
     *                              sanctioned through the normal flow (e.g. a fast-tracked reborrow)
     * @param disbursedOn           date of disbursal (never null for a minted loan)
     * @param dueDate               the salary-linked single-repayment due date
     * @param closedOn              the date the loan actually reached CLOSED/REPAID; null while open
     * @param salaryCreditDay       day-of-month (1–31) the borrower's salary lands; null if unset
     * @param status                the <b>effective</b> status ({@link
     *                              com.navix.loan.entity.Loan#effectiveStatus}) — never the stored
     *                              column, which stays ACTIVE on a loan that has gone OVERDUE
     * @param dpd                   whole days past due today (0 when not overdue); no bucket — bucketing
     *                              lives in navix-collections, which navix-loan cannot depend on
     * @param assignedOfficerId     the collections officer working this loan, if any; null when the
     *                              loan has no collection case or the case has no assignee yet
     * @param assignedOfficerName   the officer's display name, resolved via {@code StaffDirectory};
     *                              null exactly when {@code assignedOfficerId} is null
     * @param disbursalTxnRef       the outgoing disbursal's bank/UPI transaction reference
     */
    public record LoanRegisterRow(
            Long loanId,
            Long customerId,
            Long applicationId,
            String borrowerName,
            String mobile,
            String panMasked,
            int loanCycle,
            Long principalPaise,
            Long netDisbursedPaise,
            Long totalRepayablePaise,
            Long outstandingPaise,
            Long sanctionedAmountPaise,
            LocalDate sanctionedAt,
            LocalDate disbursedOn,
            LocalDate dueDate,
            LocalDate closedOn,
            Integer salaryCreditDay,
            String status,
            int dpd,
            Long assignedOfficerId,
            String assignedOfficerName,
            String disbursalTxnRef) {
    }
}
