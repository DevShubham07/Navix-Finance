package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and verifies repayments against a loan (integer paise).
 *
 * <p>Allocation is simple at the ledger level: the stored {@code loan.outstanding} is
 * {@code totalRepayable − Σ verified payments}. Proof is required before a payment counts, so only
 * VERIFIED payments reduce the balance. Partial payments are allowed; the loan CLOSES at zero.
 * The authoritative, prepayment-aware figure is {@link #outstandingAsOf} (compute-on-read).
 */
@Service
@RequiredArgsConstructor
public class RepaymentService {

    private final PaymentRepository paymentRepository;
    private final LoanRepository loanRepository;
    private final LoanMath loanMath;
    private final ApplicationFlowService applicationFlowService;

    /** Record a (possibly partial) repayment. Idempotent on {@code txnRef} per loan. */
    @Transactional
    public Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method,
                                 String txnRef, String proofUrl, LocalDate paidOn) {
        Loan loan = requireLoan(loanId);
        if (loan.getStatus() == LoanStatus.CLOSED || loan.getStatus() == LoanStatus.REPAID) {
            throw new BusinessException("LOAN_SETTLED", "Loan is already settled");
        }
        if (amountPaise <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Payment amount must be positive");
        }
        if (txnRef != null && !txnRef.isBlank()) {
            var existing = paymentRepository.findFirstByLoanIdAndTxnRef(loanId, txnRef);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        // Penalty-aware remaining as of the payment date, so a short payment on an overdue loan is
        // correctly flagged partial (the stored total excludes the accruing late penalty).
        LocalDate effectivePaidOn = paidOn != null ? paidOn : LocalDate.now();
        long remaining = outstandingAsOf(loanId, effectivePaidOn);

        Payment payment = new Payment();
        payment.setLoanId(loanId);
        payment.setAmount(amountPaise);
        payment.setMethod(method);
        payment.setStatus(PaymentStatus.PENDING_VERIFICATION);
        payment.setTxnRef(txnRef);
        payment.setProofUrl(proofUrl);
        payment.setPaidOn(effectivePaidOn);
        payment.setPartial(amountPaise < remaining);
        return paymentRepository.save(payment);
    }

    /** Confirm proof for a payment; recomputes the loan balance and closes it at zero. */
    @Transactional
    public Payment verifyPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.getStatus() != PaymentStatus.VERIFIED) {
            payment.setStatus(PaymentStatus.VERIFIED);
            paymentRepository.save(payment);
        }
        recomputeOutstanding(payment.getLoanId());
        return payment;
    }

    @Transactional(readOnly = true)
    public List<Payment> listPayments(Long loanId) {
        return paymentRepository.findByLoanId(loanId);
    }

    /** Accountant queue: every repayment still awaiting proof verification, across all loans. */
    @Transactional(readOnly = true)
    public List<Payment> listPending() {
        return paymentRepository.findByStatusOrderByIdAsc(PaymentStatus.PENDING_VERIFICATION);
    }

    /**
     * Authoritative outstanding balance at a date: principal + interest accrued to {@code asOf}
     * (capped at the scheduled tenure) + late penalty past the 1-day grace − verified payments.
     * Prepayment falls out naturally (less interest for fewer days held).
     */
    @Transactional(readOnly = true)
    public long outstandingAsOf(Long loanId, LocalDate asOf) {
        Loan loan = requireLoan(loanId);
        LocalDate at = asOf != null ? asOf : LocalDate.now();
        int tenureDays = (int) ChronoUnit.DAYS.between(loan.getDisbursedOn(), loan.getDueDate());
        int daysToAsOf = (int) Math.max(0L, ChronoUnit.DAYS.between(loan.getDisbursedOn(), at));
        int interestDays = Math.min(daysToAsOf, tenureDays);
        int rawDpd = loanMath.daysPastDue(loan.getDueDate(), at);
        int penaltyDays = Math.max(0, rawDpd - LoanMath.SALARY_GRACE_DAYS);
        long verified = paymentRepository.sumAmountByLoanIdAndStatus(loanId, PaymentStatus.VERIFIED);
        return loanMath.outstandingPaise(loan.getPrincipal(), interestDays, penaltyDays, verified);
    }

    private void recomputeOutstanding(Long loanId) {
        Loan loan = requireLoan(loanId);
        // Use the authoritative penalty-aware balance: an overdue loan must not close just because
        // the borrower paid the no-penalty stored total — the accrued late penalty is still owed.
        long owed = outstandingAsOf(loanId, LocalDate.now());
        loan.setOutstanding(owed);
        if (owed == 0L) {
            loan.setStatus(LoanStatus.CLOSED);
        }
        loanRepository.save(loan);
        // Mirror full repayment onto the application aggregate (ACTIVE/OVERDUE → CLOSED).
        if (owed == 0L) {
            applicationFlowService.closeForLoan(loanId);
        }
    }

    private Loan requireLoan(Long loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
    }
}
