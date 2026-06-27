package com.navix.loan.dto;

import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request/response DTOs for the disbursed loan + repayments (W4). Monetary fields are integer paise.
 * Application-lifecycle DTOs live in {@code ApplicationDtos}.
 */
public final class LoanDtos {

    private LoanDtos() {
    }

    /** Record a (possibly partial) repayment. */
    public record RepaymentRequest(
            @Positive long amountPaise,
            @NotNull PaymentMethod method,
            String txnRef,
            String proofUrl,
            LocalDate paidOn) {
    }

    public record LoanView(
            Long id,
            Long applicantId,
            long principalPaise,
            Long processingFeePaise,
            Long gstPaise,
            Long netDisbursedPaise,
            BigDecimal dailyInterestRate,
            LocalDate disbursedOn,
            LocalDate dueDate,
            Long totalRepayablePaise,
            Long outstandingPaise,
            LoanStatus status) {

        public static LoanView of(Loan l) {
            return of(l, l.getOutstanding(), l.getStatus());
        }

        /**
         * Build a view with a compute-on-read {@code outstandingPaise} (prepayment + late-penalty
         * aware) and an effective {@code status} (ACTIVE reads as OVERDUE once past the due date).
         * The on-time {@code totalRepayable} stays the stored figure.
         */
        public static LoanView of(Loan l, long outstandingPaise, LoanStatus status) {
            return new LoanView(l.getId(), l.getApplicantId(), l.getPrincipal(), l.getProcessingFee(),
                    l.getGst(), l.getNetDisbursed(), l.getDailyInterestRate(), l.getDisbursedOn(),
                    l.getDueDate(), l.getTotalRepayable(), outstandingPaise, status);
        }
    }

    public record PaymentView(
            Long id,
            Long loanId,
            long amountPaise,
            PaymentMethod method,
            PaymentStatus status,
            String txnRef,
            String proofUrl,
            LocalDate paidOn,
            boolean partial) {

        public static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getLoanId(), p.getAmount(), p.getMethod(), p.getStatus(),
                    p.getTxnRef(), p.getProofUrl(), p.getPaidOn(), p.isPartial());
        }
    }

    /**
     * The authoritative balance for a loan as of a date. {@code settledAmountPaise} is non-null only
     * when collections has an approved partial settlement — then {@code outstandingPaise} is the
     * settlement-capped "pay today" figure (full-and-final), letting the UI label it a settlement.
     */
    public record OutstandingView(Long loanId, LocalDate asOf, long outstandingPaise,
                                  Long settledAmountPaise) {
    }

    /**
     * One row in the accountant's company-wide transactions ledger. Synthesized (not stored):
     * a DISBURSAL (OUTGOING, money leaving NAVIX, from {@code loan.net_disbursed}) or a REPAYMENT
     * (INCOMING, from a {@code payment}). PAN is masked.
     */
    public record TransactionView(
            String id,            // synthetic: "D-{loanId}" or "P-{paymentId}"
            String type,          // DISBURSAL | REPAYMENT
            String direction,     // OUTGOING | INCOMING
            Long loanId,
            Long applicantId,
            String borrowerName,
            String panMasked,
            long amountPaise,
            String txnRef,
            String status,
            LocalDate date) {
    }
}
