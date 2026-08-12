package com.navix.loan.service;

import com.navix.common.collections.SettlementDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.RepaymentRecordedEvent;
import com.navix.common.notification.event.RepaymentRejectedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final SettlementDirectory settlementDirectory;
    private final ApplicationEventPublisher eventPublisher;

    /** Record a (possibly partial) repayment. Idempotent on {@code txnRef} per loan. */
    @Transactional
    public Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method,
                                 String txnRef, String proofUrl, LocalDate paidOn) {
        Loan loan = requireLoan(loanId);
        requireOwnLoanIfBorrower(loan);
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
        Payment saved = paymentRepository.save(payment);
        eventPublisher.publishEvent(new RepaymentRecordedEvent(
                loanId, loan.getCustomerId(), saved.getId(), amountPaise, Instant.now()));
        return saved;
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
        Loan loan = requireLoan(payment.getLoanId());
        eventPublisher.publishEvent(new RepaymentVerifiedEvent(
                loan.getId(), loan.getCustomerId(), payment.getId(), payment.getAmount(),
                loan.getStatus() == LoanStatus.CLOSED, Instant.now()));
        return payment;
    }

    /** The fixed rejection-reason picklist — kept here (not a DB enum/check) to match how
     *  {@link PaymentMethod}/{@link PaymentStatus} are already enforced above the DB in this schema. */
    private static final java.util.Set<String> REJECTION_REASONS = java.util.Set.of(
            "WRONG_REFERENCE", "AMOUNT_MISMATCH", "NOT_RECEIVED", "UNREADABLE_PROOF", "OTHER");

    /**
     * Reject a recorded payment (the accountant couldn't match the proof / transfer). Terminal —
     * a rejected payment never counts toward the outstanding (only VERIFIED payments are summed),
     * so no balance recompute is needed. A VERIFIED payment cannot be rejected. {@code reason} is
     * required and must be one of {@link #REJECTION_REASONS}; {@code note} is optional free text.
     * Notifies the borrower (IN_APP/EMAIL carry the reason; the SMS body is DLT-locked and unchanged).
     */
    @Transactional
    public Payment rejectPayment(Long paymentId, String reason, String note) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REJECTION_REASON_REQUIRED", "A rejection reason is required");
        }
        if (!REJECTION_REASONS.contains(reason)) {
            throw new BusinessException("INVALID_REJECTION_REASON",
                    "reason must be one of " + REJECTION_REASONS);
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.getStatus() == PaymentStatus.VERIFIED) {
            throw new BusinessException("PAYMENT_ALREADY_VERIFIED", "A verified payment cannot be rejected");
        }
        if (payment.getStatus() != PaymentStatus.REJECTED) {
            payment.setStatus(PaymentStatus.REJECTED);
            payment.setRejectionReason(reason);
            payment.setRejectionNote(note);
            paymentRepository.save(payment);
        }
        Loan loan = requireLoan(payment.getLoanId());
        eventPublisher.publishEvent(new RepaymentRejectedEvent(
                loan.getId(), loan.getCustomerId(), payment.getId(), payment.getAmount(),
                reason, note, Instant.now()));
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
     *
     * <p><b>Approved settlement:</b> when collections has an approved partial settlement for the loan
     * (via {@link SettlementDirectory}), the agreed full-and-final amount caps the payable. Because a
     * settlement is a concession it can only <i>reduce</i> what the borrower owes — never increase it —
     * so the result is {@code min(formulaOwed, settlement − verified)}. The borrower's repay page,
     * dashboard and the close-at-zero logic all read this method, so an approved settlement becomes the
     * borrower-facing payable and the loan closes once the settled amount is paid.
     */
    @Transactional(readOnly = true)
    public long outstandingAsOf(Long loanId, LocalDate asOf) {
        return outstandingBreakdownAsOf(loanId, asOf).outstandingPaise();
    }

    /**
     * The itemized make-up of {@link #outstandingAsOf}: the same accrued-interest, late-penalty and
     * verified-payment figures the net balance is built from, exposed individually so the UI can show
     * a full cost breakdown without re-deriving (and forking) the math. {@code interestPaise} and
     * {@code penaltyPaise} are the <i>scheduled/accrued</i> amounts as of {@code asOf}; when
     * {@code settledAmountPaise} is non-null the net {@code outstandingPaise} is the settlement-capped
     * full-and-final figure, so the components may then sum to more than the net.
     *
     * <p>{@code interestDays} / {@code penaltyDays} are the <i>day counts</i> those two amounts were
     * charged over — interest days are capped at the tenure (interest stops accruing at the due date)
     * and penalty days exclude the {@link LoanMath#SALARY_GRACE_DAYS} grace and are capped at
     * {@link LoanMath#LATE_PENALTY_CAP_DAYS}. Exposed so collections can show "1%/day × N days"
     * rather than an unexplained rupee figure, without re-deriving (and forking) the math.
     */
    public record OutstandingBreakdown(long outstandingPaise, long interestPaise, long penaltyPaise,
                                       long verifiedPaise, Long settledAmountPaise,
                                       int interestDays, int penaltyDays) {
    }

    @Transactional(readOnly = true)
    public OutstandingBreakdown outstandingBreakdownAsOf(Long loanId, LocalDate asOf) {
        Loan loan = requireLoan(loanId);
        LocalDate at = asOf != null ? asOf : LocalDate.now();
        // A closed loan's balance is frozen at the day it closed — otherwise a loan closed months ago
        // keeps accruing late penalty against "today" and reports a phantom balance.
        if (loan.getClosedOn() != null && at.isAfter(loan.getClosedOn())) {
            at = loan.getClosedOn();
        }
        int tenureDays = (int) ChronoUnit.DAYS.between(loan.getDisbursedOn(), loan.getDueDate());
        int daysToAsOf = (int) Math.max(0L, ChronoUnit.DAYS.between(loan.getDisbursedOn(), at));
        int interestDays = Math.min(daysToAsOf, tenureDays + LoanMath.SALARY_GRACE_DAYS);
        int rawDpd = loanMath.daysPastDue(loan.getDueDate(), at);
        int penaltyDays = Math.max(0, rawDpd - LoanMath.SALARY_GRACE_DAYS);
        long verified = paymentRepository.sumAmountByLoanIdAndStatus(loanId, PaymentStatus.VERIFIED);
        long interest = loanMath.interestPaise(loan.getPrincipal(), interestDays);
        long penalty = loanMath.latePenaltyPaise(loan.getPrincipal(), penaltyDays);
        long formulaOwed = loanMath.outstandingPaise(loan.getPrincipal(), interestDays, penaltyDays, verified);
        Long settled = settlementDirectory.approvedSettlementAmount(loanId).orElse(null);
        long owed = settled != null ? Math.min(formulaOwed, Math.max(0L, settled - verified)) : formulaOwed;
        // Report the days actually CHARGED (penalty is capped inside latePenaltyPaise), so the
        // displayed "2%/day × N days" reconciles with penaltyPaise instead of overstating it.
        int chargedPenaltyDays = Math.min(penaltyDays, LoanMath.LATE_PENALTY_CAP_DAYS);
        return new OutstandingBreakdown(owed, interest, penalty, verified, settled,
                Math.max(0, interestDays), chargedPenaltyDays);
    }

    /**
     * The approved full-and-final settlement amount (paise) for a loan, if collections has one — for
     * surfacing on the borrower's repay page (so the capped "pay today" figure is shown as a
     * settlement, not the normal balance). Empty when no approved settlement applies.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> approvedSettlementAmount(Long loanId) {
        return settlementDirectory.approvedSettlementAmount(loanId);
    }

    private void recomputeOutstanding(Long loanId) {
        Loan loan = requireLoan(loanId);
        // Use the authoritative penalty-aware balance: an overdue loan must not close just because
        // the borrower paid the no-penalty stored total — the accrued late penalty is still owed.
        long owed = outstandingAsOf(loanId, LocalDate.now());
        loan.setOutstanding(owed);
        if (owed == 0L) {
            loan.setStatus(LoanStatus.CLOSED);
            // Freeze the balance as of the day it actually closed: the latest VERIFIED payment's
            // paidOn (falling back to today, though a verified payment should always exist here since
            // owed just hit zero) — so outstandingBreakdownAsOf never keeps accruing penalty past this
            // date for a long-closed loan.
            LocalDate closingDate = paymentRepository.findByLoanId(loanId).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.VERIFIED && p.getPaidOn() != null)
                    .map(Payment::getPaidOn)
                    .max(LocalDate::compareTo)
                    .orElse(LocalDate.now());
            loan.setClosedOn(closingDate);
        }
        loanRepository.save(loan);
        // Mirror full repayment onto the application aggregate (ACTIVE/OVERDUE → CLOSED).
        if (owed == 0L) {
            applicationFlowService.closeForLoan(loanId);
        }
    }

    /**
     * A borrower may only record payments against their <em>own</em> loan.
     *
     * <p>Without this any borrower could post a payment onto a stranger's loan: the row lands
     * PENDING_VERIFICATION on that loan, and the only thing standing between it and a real balance
     * reduction is an accountant noticing. Staff roles are unrestricted — recording on a borrower's
     * behalf (a branch walk-in, a phoned-in reference) is legitimate.
     */
    private void requireOwnLoanIfBorrower(Loan loan) {
        var actor = com.navix.common.security.ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            return;
        }
        if (!String.valueOf(loan.getCustomerId()).equals(actor.id())) {
            throw new BusinessException("FORBIDDEN", "You can only record payments on your own loan");
        }
    }

    private Loan requireLoan(Long loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
    }
}
