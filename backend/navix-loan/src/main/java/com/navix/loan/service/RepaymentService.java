package com.navix.loan.service;

import com.navix.common.collections.SettlementDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.storage.DocumentStoragePort;
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
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    /**
     * Every date in this class is an <em>Indian</em> calendar date. The server clock runs UTC in ECS,
     * so a bare {@code LocalDate.now(IST)} reports yesterday between 00:00 and 05:30 IST — one wrong day
     * of interest, and a wrong DPD/due boundary. Matches {@code OfferService}/{@code DashboardService}.
     */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** How far back a BORROWER may date their own payment. Staff (and ADMIN) are not bound by it. */
    private static final int BORROWER_BACKDATE_DAYS = 3;

    private final PaymentRepository paymentRepository;
    private final LoanRepository loanRepository;
    private final LoanMath loanMath;
    private final ApplicationFlowService applicationFlowService;
    private final SettlementDirectory settlementDirectory;
    private final ApplicationEventPublisher eventPublisher;
    private final DsaCommissionService dsaCommissionService;
    private final DocumentStoragePort storage;

    /**
     * Record a (possibly partial) repayment. Idempotent on {@code txnRef} per loan.
     *
     * <p>A screenshot is mandatory ONLY when the caller is the borrower themselves — staff recording
     * on a borrower's behalf (a branch walk-in, a phoned-in reference) legitimately has no upload, and
     * collections' validated-payment path (proof is deliberately flexible there — screenshot / text /
     * transaction id, revamp.md decision) also flows through this same method. Gate on the actor, not
     * a DTO-level constraint, so those two paths stay unaffected.
     */
    @Transactional
    public Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method,
                                 String txnRef, String proofUrl, LocalDate paidOn) {
        Loan loan = requireLoan(loanId);
        requireOwnLoanIfBorrower(loan);
        if ("BORROWER".equals(ActorContext.get().role()) && (proofUrl == null || proofUrl.isBlank())) {
            throw new BusinessException("PAYMENT_PROOF_REQUIRED",
                    "Attach a screenshot of your payment before submitting.");
        }
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
        LocalDate effectivePaidOn = requireSanePaidOn(loan, paidOn);
        // Penalty-aware remaining as of the payment date, so a short payment on an overdue loan is
        // correctly flagged partial (the stored total excludes the accruing late penalty).
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

    /**
     * The payment date decides how much interest was owed (see {@link #recomputeOutstanding}), so it
     * is a money input and validated like one — it was previously accepted unchecked from every
     * caller, including the borrower's own app.
     *
     * <p>Two rules bind everybody: a payment cannot be made in the future, and it cannot predate the
     * disbursal that created the debt. On top of that a BORROWER may only date a payment within the
     * last few days — self-service backdating is how an overdue borrower would erase their own
     * penalty. Staff backdate legitimately (a branch walk-in, a reconciled bank line, collections'
     * validated payments), so the window is borrower-only, not a blanket rule.
     */
    private LocalDate requireSanePaidOn(Loan loan, LocalDate paidOn) {
        LocalDate today = LocalDate.now(IST);
        LocalDate effective = paidOn != null ? paidOn : today;
        // One day of slack, not zero: the borrower's app sends its own LOCAL date, so a handset in a
        // timezone ahead of IST (or one with a skewed clock) legitimately posts tomorrow's date for
        // a payment made right now. Rejecting on the nose would turn that into an unexplainable
        // failure on a real payment; a day beyond that is not a clock, it is a claim.
        if (effective.isAfter(today.plusDays(1))) {
            throw new BusinessException("INVALID_PAID_ON", "Payment date cannot be in the future");
        }
        // Never let a future date drive the interest arithmetic, whatever the handset said.
        if (effective.isAfter(today)) {
            effective = today;
        }
        if (loan.getDisbursedOn() != null && effective.isBefore(loan.getDisbursedOn())) {
            throw new BusinessException("INVALID_PAID_ON",
                    "Payment date cannot be before the loan was disbursed");
        }
        if ("BORROWER".equals(ActorContext.get().role())
                && effective.isBefore(today.minusDays(BORROWER_BACKDATE_DAYS))) {
            throw new BusinessException("INVALID_PAID_ON",
                    "Payment date is too far in the past — contact support to record an older payment.");
        }
        return effective;
    }

    /** Confirm proof for a payment; recomputes the loan balance and closes it at zero. */
    @Transactional
    public Payment verifyPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.getStatus() != PaymentStatus.VERIFIED) {
            payment.setStatus(PaymentStatus.VERIFIED);
            stampDecider(payment);
            paymentRepository.save(payment);
        }
        recomputeOutstanding(payment.getLoanId());
        Loan loan = requireLoan(payment.getLoanId());
        eventPublisher.publishEvent(new RepaymentVerifiedEvent(
                loan.getId(), loan.getCustomerId(), payment.getId(), payment.getAmount(),
                loan.getStatus() == LoanStatus.CLOSED, Instant.now()));
        return payment;
    }

    /**
     * Record WHO decided this payment and when (V59) — the Accountant's verify/reject was previously
     * unattributable, which left their core daily work invisible to the staff-performance dashboard.
     * Deliberately not {@code updatedBy}: that holds a mutable display name and any later write to
     * the row overwrites it. Which way the decision went stays on {@code status}.
     */
    private void stampDecider(Payment payment) {
        try {
            payment.setDecidedBy(Long.valueOf(ActorContext.get().id()));
        } catch (RuntimeException e) {
            // No resolvable staff id (system/scheduler path) — leave it null rather than guess.
            payment.setDecidedBy(null);
        }
        payment.setDecidedAt(Instant.now());
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
            stampDecider(payment);
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
     * {@link PaymentView} with {@code proofUrl} resolved to a short-lived presigned GET — the DTO
     * factories only know the raw S3 key, and every read path (borrower's own list, the accountant's
     * verify queue, a staff loan-detail dialog) needs a link the browser can actually open.
     */
    public PaymentView view(Payment p) {
        return PaymentView.ofWithResolvedProof(p, presignedProof(p));
    }

    /** {@link #view(Payment)} + the staff-only borrower context (verify queue). */
    public PaymentView view(Payment p, Long customerId, String customerName) {
        return PaymentView.ofWithResolvedProof(p, customerId, customerName, presignedProof(p));
    }

    private String presignedProof(Payment p) {
        String key = p.getProofUrl();
        return key == null || key.isBlank() ? null : storage.presignDownload(key);
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
        LocalDate at = asOf != null ? asOf : LocalDate.now(IST);
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
     * The batched twin of {@link #outstandingAsOf}: the same penalty/prepayment-aware balance for a
     * whole set of loans in a small, fixed number of queries instead of one round trip per loan —
     * {@link #outstandingBreakdownAsOf} alone costs 3 DB queries per loan (verified-payment sum, the
     * loan reload inside {@code requireLoan}, and the settlement lookup), so a 50-row page would
     * otherwise be ~150 queries. Callers must already hold the {@link Loan} rows (no reload here).
     *
     * <p>Runs <b>the same formula</b> {@link #outstandingBreakdownAsOf} uses, in memory, per loan —
     * this is deliberate: the loan register, the repay page, and collections must all agree on
     * "amount owed", so the math is not allowed to fork into a second implementation.
     *
     * <p>The verified-payment sums are pre-fetched in one grouped query
     * ({@link PaymentRepository#sumAmountByLoanIdInAndStatus}). The approved-settlement lookup stays
     * per-loan ({@link SettlementDirectory} exposes only a single-loan read) — settlements are rare
     * (most loans have none), so this remaining per-loan call is far cheaper than the payment-sum
     * query it replaces and does not undermine the batching.
     *
     * @param loans the loans to price (any subset; duplicates/foreign loans are harmless)
     * @param asOf  the as-of date (defaults to today when null)
     * @return loan id → outstanding paise, for every loan passed in
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> outstandingForAll(List<Loan> loans, LocalDate asOf) {
        if (loans.isEmpty()) {
            return Map.of();
        }
        LocalDate at = asOf != null ? asOf : LocalDate.now(IST);
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        Map<Long, Long> verifiedByLoanId = paymentRepository
                .sumAmountByLoanIdInAndStatus(loanIds, PaymentStatus.VERIFIED).stream()
                .collect(Collectors.toMap(PaymentRepository.LoanAmount::getLoanId,
                        PaymentRepository.LoanAmount::getTotal));

        Map<Long, Long> result = new HashMap<>();
        for (Loan loan : loans) {
            LocalDate loanAt = at;
            // Same freeze-at-close rule as outstandingBreakdownAsOf.
            if (loan.getClosedOn() != null && loanAt.isAfter(loan.getClosedOn())) {
                loanAt = loan.getClosedOn();
            }
            int tenureDays = (int) ChronoUnit.DAYS.between(loan.getDisbursedOn(), loan.getDueDate());
            int daysToAsOf = (int) Math.max(0L, ChronoUnit.DAYS.between(loan.getDisbursedOn(), loanAt));
            int interestDays = Math.min(daysToAsOf, tenureDays + LoanMath.SALARY_GRACE_DAYS);
            int rawDpd = loanMath.daysPastDue(loan.getDueDate(), loanAt);
            int penaltyDays = Math.max(0, rawDpd - LoanMath.SALARY_GRACE_DAYS);
            long verified = verifiedByLoanId.getOrDefault(loan.getId(), 0L);
            long formulaOwed = loanMath.outstandingPaise(loan.getPrincipal(), interestDays, penaltyDays, verified);
            Long settled = settlementDirectory.approvedSettlementAmount(loan.getId()).orElse(null);
            long owed = settled != null ? Math.min(formulaOwed, Math.max(0L, settled - verified)) : formulaOwed;
            result.put(loan.getId(), owed);
        }
        return result;
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

    /**
     * Recompute the cached balance and close the loan at zero.
     *
     * <p><b>Settlement is evaluated as of the payment date, never the verification date.</b> The
     * accountant's confirmation is bookkeeping, not an economic event: a borrower who paid in full on
     * the 5th owes nothing further because it was verified on the 7th. Evaluating at "now" charged
     * two extra days of interest (and late penalty), which could leave a fully-paid loan open and
     * accruing forever — every subsequent recompute moved the goalposts by another day. So the
     * closure question is asked as of the latest VERIFIED payment's {@code paidOn}.
     *
     * <p>A REJECTED payment is never summed, so rejecting one leaves accrual running normally — which
     * is the intended asymmetry: a failed payment costs the borrower the days, a slow verifier does not.
     *
     * <p>When the loan does <em>not</em> clear (a partial payment, or nothing verified yet) the cached
     * balance keeps tracking today, because that is still what the borrower owes if they pay now.
     */
    @Transactional
    public void recomputeOutstanding(Long loanId) {
        Loan loan = requireLoan(loanId);
        LocalDate settledOn = latestVerifiedPaidOn(loanId).orElse(LocalDate.now(IST));
        // Use the authoritative penalty-aware balance: an overdue loan must not close just because
        // the borrower paid the no-penalty stored total — the accrued late penalty is still owed.
        long owedAtPayment = outstandingAsOf(loanId, settledOn);

        if (owedAtPayment > 0L) {
            loan.setOutstanding(outstandingAsOf(loanId, LocalDate.now(IST)));
            loanRepository.save(loan);
            return;
        }
        loan.setOutstanding(0L);
        loan.setStatus(LoanStatus.CLOSED);
        // Freeze the balance as of the day it actually closed — the same date the closure was judged
        // on — so outstandingBreakdownAsOf never keeps accruing penalty past it for a closed loan.
        loan.setClosedOn(settledOn);
        loanRepository.save(loan);
        // Mirror full repayment onto the application aggregate (ACTIVE/OVERDUE → CLOSED).
        applicationFlowService.closeForLoan(loanId);
        // DSA commission maturity: flip ACCRUED -> PAYABLE (or VOID on an approved settlement /
        // default / write-off). A no-op when this loan has no DSA commission.
        dsaCommissionService.onLoanClosed(loanId);
    }

    /**
     * The figure the closure decision is made on: the balance as of the day the borrower last
     * actually paid. Zero means the loan is settled, however late the verification was. Exposed
     * read-only so the ADMIN maintenance sweep can report what a recompute would do before doing it.
     */
    @Transactional(readOnly = true)
    public long settlementBalance(Long loanId) {
        return outstandingAsOf(loanId, latestVerifiedPaidOn(loanId).orElse(LocalDate.now(IST)));
    }

    /** The date the borrower last actually paid, over VERIFIED payments only. */
    private java.util.Optional<LocalDate> latestVerifiedPaidOn(Long loanId) {
        return paymentRepository.findByLoanId(loanId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.VERIFIED && p.getPaidOn() != null)
                .map(Payment::getPaidOn)
                .max(LocalDate::compareTo);
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
