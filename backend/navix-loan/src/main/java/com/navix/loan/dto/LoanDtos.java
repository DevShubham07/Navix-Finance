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
            return new LoanView(l.getId(), l.getApplicantId(), l.getPrincipal(), l.getProcessingFee(),
                    l.getGst(), l.getNetDisbursed(), l.getDailyInterestRate(), l.getDisbursedOn(),
                    l.getDueDate(), l.getTotalRepayable(), l.getOutstanding(), l.getStatus());
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

    public record OutstandingView(Long loanId, LocalDate asOf, long outstandingPaise) {
    }
}
