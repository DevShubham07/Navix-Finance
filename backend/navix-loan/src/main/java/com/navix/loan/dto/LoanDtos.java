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

    /**
     * Reject a recorded payment. {@code reason} is required and must be one of the fixed picklist
     * codes ({@code WRONG_REFERENCE}, {@code AMOUNT_MISMATCH}, {@code NOT_RECEIVED},
     * {@code UNREADABLE_PROOF}, {@code OTHER}); {@code note} is optional free text.
     */
    public record RejectRepaymentRequest(@NotNull String reason, String note) {
    }

    public record LoanView(
            Long id,
            Long customerId,
            long principalPaise,
            Long processingFeePaise,
            Long gstPaise,
            Long netDisbursedPaise,
            BigDecimal dailyInterestRate,
            LocalDate disbursedOn,
            LocalDate dueDate,
            Long totalRepayablePaise,
            Long outstandingPaise,
            LoanStatus status,
            String disbursalTxnRef,
            /** When the loan was settled in full (V50). Null while it is still running. */
            LocalDate closedOn) {

        public static LoanView of(Loan l) {
            return of(l, l.getOutstanding(), l.getStatus());
        }

        /**
         * Build a view with a compute-on-read {@code outstandingPaise} (prepayment + late-penalty
         * aware) and an effective {@code status} (ACTIVE reads as OVERDUE once past the due date).
         * The on-time {@code totalRepayable} stays the stored figure.
         */
        public static LoanView of(Loan l, long outstandingPaise, LoanStatus status) {
            return new LoanView(l.getId(), l.getCustomerId(), l.getPrincipal(), l.getProcessingFee(),
                    l.getGst(), l.getNetDisbursed(), l.getDailyInterestRate(), l.getDisbursedOn(),
                    l.getDueDate(), l.getTotalRepayable(), outstandingPaise, status,
                    l.getDisbursalTxnRef(), l.getClosedOn());
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
            boolean partial,
            Long customerId,
            String customerName,
            // Set only when status == REJECTED (item 5): a fixed picklist code + optional free text.
            String rejectionReason,
            String rejectionNote) {

        public static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getLoanId(), p.getAmount(), p.getMethod(), p.getStatus(),
                    p.getTxnRef(), p.getProofUrl(), p.getPaidOn(), p.isPartial(), null, null,
                    p.getRejectionReason(), p.getRejectionNote());
        }

        /** Staff queue variant: associates a repayment with the borrower without exposing it to borrower APIs. */
        public static PaymentView of(Payment p, Long customerId, String customerName) {
            return new PaymentView(p.getId(), p.getLoanId(), p.getAmount(), p.getMethod(), p.getStatus(),
                    p.getTxnRef(), p.getProofUrl(), p.getPaidOn(), p.isPartial(), customerId, customerName,
                    p.getRejectionReason(), p.getRejectionNote());
        }

        /**
         * As {@link #of(Payment)}, but {@code proofUrl} is a caller-resolved value (a short-lived
         * presigned GET, never the raw S3 key) — the object storage key {@link Payment#getProofUrl()}
         * holds isn't fetchable by a browser directly. Used by every read path so a borrower or staff
         * screen showing a transaction id can also link straight to the uploaded proof.
         */
        public static PaymentView ofWithResolvedProof(Payment p, String presignedProofUrl) {
            return new PaymentView(p.getId(), p.getLoanId(), p.getAmount(), p.getMethod(), p.getStatus(),
                    p.getTxnRef(), presignedProofUrl, p.getPaidOn(), p.isPartial(), null, null,
                    p.getRejectionReason(), p.getRejectionNote());
        }

        /** {@link #of(Payment, Long, String)} + {@link #ofWithResolvedProof(Payment, String)} combined. */
        public static PaymentView ofWithResolvedProof(Payment p, Long customerId, String customerName,
                                                       String presignedProofUrl) {
            return new PaymentView(p.getId(), p.getLoanId(), p.getAmount(), p.getMethod(), p.getStatus(),
                    p.getTxnRef(), presignedProofUrl, p.getPaidOn(), p.isPartial(), customerId, customerName,
                    p.getRejectionReason(), p.getRejectionNote());
        }
    }

    /**
     * The authoritative balance for a loan as of a date. {@code settledAmountPaise} is non-null only
     * when collections has an approved partial settlement — then {@code outstandingPaise} is the
     * settlement-capped "pay today" figure (full-and-final), letting the UI label it a settlement.
     *
     * <p>{@code interestPaise} / {@code penaltyPaise} / {@code verifiedPaise} itemize how the net
     * balance is made up — the accrued interest and late penalty as of {@code asOf}, and the sum of
     * verified payments so far — so a loan-details popup can show the full cost breakdown. When a
     * settlement caps the figure, these components may sum to more than {@code outstandingPaise}.
     *
     * <p>{@code interestDays} / {@code penaltyDays} are the day counts those amounts were charged
     * over (interest capped at tenure plus the 1-day grace; penalty starts after grace and is capped at 30), so the
     * UI can show the working — "1%/day × 27 days" — instead of a bare rupee figure.
     */
    public record OutstandingView(Long loanId, LocalDate asOf, long outstandingPaise,
                                  Long settledAmountPaise, long interestPaise, long penaltyPaise,
                                  long verifiedPaise, int interestDays, int penaltyDays) {
    }

    /**
     * One row in the accountant's company-wide transactions ledger. Synthesized (not stored):
     * a DISBURSAL (OUTGOING, money leaving DhanBoost, from {@code loan.net_disbursed}) or a REPAYMENT
     * (INCOMING, from a {@code payment}). PAN is returned in full (staff-only ledger).
     */
    public record TransactionView(
            String id,            // synthetic: "D-{loanId}" or "P-{paymentId}"
            String type,          // DISBURSAL | REPAYMENT
            String direction,     // OUTGOING | INCOMING
            Long loanId,
            Long customerId,
            String borrowerName,
            String pan,
            long amountPaise,
            String txnRef,
            String status,
            LocalDate date,
            // Presigned GET for the borrower-uploaded repayment screenshot — REPAYMENT rows only,
            // null for a DISBURSAL row (nothing was ever uploaded against it) and for a REPAYMENT
            // row with no proof on file.
            String proofUrl) {
    }
}
