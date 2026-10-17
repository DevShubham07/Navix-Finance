package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.verification.MandatePort;
import com.navix.common.verification.MandatePort.DebitOutcome;
import com.navix.common.verification.MandatePort.DebitPlan;
import com.navix.common.verification.MandatePort.DebitRequest;
import com.navix.loan.entity.Mandate;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.MandateDebit.Status;
import com.navix.loan.entity.MandateDebit.Trigger;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Presents NACH debits against a registered mandate and applies their outcomes. Every presentation moves
 * REAL money and the provider has NO idempotency key, so this class is where a double charge is prevented:
 * <ol>
 *   <li>a loan-scoped advisory lock + the partial unique index on in-flight rows serialise presentations;</li>
 *   <li>the row is COMMITTED before the provider is called, so a crash leaves a recovery handle;</li>
 *   <li>after any failed/timed-out create we look the debit up by its {@code unique_key} and never blind-retry;</li>
 *   <li>{@code FAILED_TECHNICAL} means "outcome unknown" and sits outside the unique index, so such a row is
 *       recovered by key before anything new is presented.</li>
 * </ol>
 * Deliberately NOT {@code @Transactional}: the provider call must happen between two committed transactions,
 * and {@code creditCollectionPayment} must run in its own so a caught {@code LOAN_SETTLED} cannot poison ours.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DebitService {

    /** ECS runs UTC; all dates here are Indian calendar dates (see RepaymentService.IST). */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<Status> IN_FLIGHT = EnumSet.of(Status.SCHEDULED, Status.IN_PROGRESS);
    private static final Set<String> LIVE_LOAN = Set.of("ACTIVE", "OVERDUE", "IN_COLLECTIONS");
    private static final Pattern NARRATION = Pattern.compile("^[0-9A-Za-z& .]{1,20}$");
    private static final Pattern UNIQUE_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");
    static final String NARRATION_TEXT = "DhanBoost repayment";
    /** Keeps the loan-keyed advisory lock out of the customer-keyed one MandateService takes. */
    private static final long LOCK_NAMESPACE = 1L << 40;
    /**
     * The only reject codes that may be re-presented. NPCI flags exactly one return reason "Allow RPN: Yes" —
     * balance insufficient (docs/digio/06 section 5). Anything else, and anything not listed, is terminal: failing
     * closed costs one missed retry, failing open burns the monthly allowance. Numeric NPCI codes are
     * deliberately absent until a live failure_code has been observed; add them when seen.
     */
    private static final Set<String> REPRESENTABLE_CODES =
            Set.of("BALANCE_INSUFFICIENT", "INSUFFICIENT_BALANCE", "INSUFFICIENT_FUNDS");

    private final MandateDebitRepository debits;
    private final MandateService mandateService;
    private final LoanDirectory loans;
    private final RepaymentService repayments;
    private final MandatePort port;
    private final PaymentRepository payments;
    private final TransactionTemplate tx;

    /** The lender's settlement account; blank means debits are not configured and we refuse to present. */
    @Value("${navix.digio.corporate-account-number:}")
    private String corporateAccountNumber;

    /**
     * What callers (sweep, collections) see. Deliberately no provider message and no failure code:
     * a raw provider string or internal code must never reach a user-facing path.
     */
    public record DebitView(Long id, Long loanId, Status status, long amountPaise, LocalDate settlementDate,
                            int attemptNo, Trigger triggeredBy, String utr, boolean overpayment,
                            boolean representable) {
    }

    private record Prepared(MandateDebit row, DebitRequest request, boolean adopted) {
    }

    /**
     * Present one debit for {@code loanId}. {@code amountPaise == null} means the full outstanding as of today
     * (IST). Throws {@link BusinessException} with a stable code for every refused guard.
     */
    public DebitView present(Long loanId, Long amountPaise, Trigger trigger, Long actorId) {
        Prepared p = tx.execute(s -> prepare(loanId, amountPaise, trigger, actorId));
        if (p.adopted()) {
            return view(p.row());
        }
        DebitPlan plan;
        try {
            plan = port.presentDebit(p.request());
        } catch (RuntimeException e) {
            log.warn("Debit presentation failed debitId={} exception={}", p.row().getId(), e.getClass().getSimpleName());
            plan = recover(p.row());
        }
        apply(p.row(), plan);
        tx.execute(s -> debits.save(p.row()));
        return view(p.row());
    }

    /** Read the provider's outcome for one in-flight debit and apply it. Safe to repeat. */
    public DebitView reconcile(Long debitId) {
        MandateDebit row = debits.findById(debitId)
                .orElseThrow(() -> new BusinessException("DEBIT_NOT_FOUND", "Debit not found"));
        if (!IN_FLIGHT.contains(row.getStatus()) && row.getStatus() != Status.FAILED_TECHNICAL) {
            return view(row); // terminal: monotonic, never revisited
        }
        if (row.getDigioTxnId() == null) {
            // Create returned no NPT id (or never returned at all): resolve it from the plan read-back.
            Optional<DebitPlan> found = lookup(row.getUniqueKey());
            if (found.isEmpty()) {
                return view(row);
            }
            apply(row, found.get());
            if (row.getDigioTxnId() == null) {
                tx.execute(s -> debits.save(row));
                return view(row);
            }
        }
        DebitOutcome outcome;
        try {
            outcome = port.debitStatus(row.getDigioTxnId());
        } catch (RuntimeException e) {
            log.warn("Debit status read failed debitId={} exception={}", row.getId(), e.getClass().getSimpleName());
            throw new BusinessException("DEBIT_UNAVAILABLE", "The debit status could not be read just now");
        }
        applyOutcome(row, outcome);
        return view(row);
    }

    /** True only for a failed debit whose reject code NPCI allows to be re-presented. Unknown is terminal. */
    public boolean eligibleForRepresentation(MandateDebit d) {
        if (d == null || d.getStatus() != Status.FAILED || d.getFailureCode() == null) {
            return false;
        }
        String code = d.getFailureCode().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return REPRESENTABLE_CODES.contains(code);
    }

    // ---- presentation ------------------------------------------------------------------------------------

    private Prepared prepare(Long loanId, Long amountPaise, Trigger trigger, Long actorId) {
        debits.lockLoan(LOCK_NAMESPACE | loanId);
        if (debits.existsByLoanIdAndStatusIn(loanId, IN_FLIGHT)) {
            throw new BusinessException("DEBIT_IN_FLIGHT", "A debit is already in progress for this loan");
        }

        // FAILED_TECHNICAL is outside the unique index on purpose; recover by key BEFORE presenting again.
        MandateDebit latest = debits.findFirstByLoanIdOrderByAttemptNoDesc(loanId).orElse(null);
        MandateDebit reuse = null;
        if (latest != null && latest.getStatus() == Status.FAILED_TECHNICAL) {
            Optional<DebitPlan> found = lookup(latest.getUniqueKey());
            if (found.isPresent()) {
                apply(latest, found.get());
                debits.save(latest);
                return new Prepared(latest, null, true);
            }
            reuse = latest; // nothing exists under the key: re-present with the IDENTICAL key (provider dedupes)
        }

        LoanSummary loan = loans.findLoan(loanId)
                .orElseThrow(() -> new BusinessException("LOAN_NOT_FOUND", "Loan not found"));
        LocalDate today = LocalDate.now(IST);
        if (loan.status() == null || !LIVE_LOAN.contains(loan.status())) {
            throw new BusinessException("LOAN_NOT_DEBITABLE", "This loan cannot be debited");
        }
        // DPD from the due date, not the end of grace (same arithmetic as DpdCalculator.daysPastDue, which
        // lives in navix-collections and is not on this module's classpath).
        int dpd = loan.dueDate() == null || !today.isAfter(loan.dueDate()) ? 0
                : (int) ChronoUnit.DAYS.between(loan.dueDate(), today);
        if (dpd < 1) {
            throw new BusinessException("LOAN_NOT_OVERDUE", "This loan is not overdue");
        }
        Mandate mandate = mandateService.usableMandateForLoan(loanId)
                .orElseThrow(() -> new BusinessException("NO_USABLE_MANDATE", "No active mandate for this loan"));
        if (mandate.getUmrn() == null || mandate.getUmrn().isBlank()) {
            throw new BusinessException("NO_USABLE_MANDATE", "No active mandate for this loan");
        }
        if (corporateAccountNumber == null || corporateAccountNumber.isBlank()) {
            throw new BusinessException("DEBIT_NOT_CONFIGURED", "Debits are not configured");
        }

        long outstanding = repayments.outstandingAsOf(loanId, today);
        if (outstanding <= 0) {
            throw new BusinessException("NOTHING_OUTSTANDING", "Nothing is outstanding on this loan");
        }
        long amount = amountPaise == null ? outstanding : amountPaise;
        if (amount <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "The debit amount must be positive");
        }
        if (amount > outstanding) {
            throw new BusinessException("AMOUNT_EXCEEDS_OUTSTANDING", "The amount is more than is owed");
        }
        if (amount > mandate.getMaximumAmountPaise()) {
            throw new BusinessException("AMOUNT_EXCEEDS_MANDATE", "The amount is above the mandate limit");
        }
        if (amount > Integer.MAX_VALUE) { // Digio's amount_in_paise is int32
            throw new BusinessException("AMOUNT_EXCEEDS_MANDATE", "The amount is above the mandate limit");
        }
        // T-1 cutoff: what we request today settles tomorrow at the earliest.
        LocalDate settle = today.plusDays(1);
        if ((mandate.getFirstCollectionDate() != null && settle.isBefore(mandate.getFirstCollectionDate()))
                || (mandate.getFinalCollectionDate() != null && settle.isAfter(mandate.getFinalCollectionDate()))) {
            throw new BusinessException("OUTSIDE_MANDATE_WINDOW", "The debit date is outside the mandate period");
        }

        int attemptNo = reuse != null ? reuse.getAttemptNo() : latest == null ? 1 : latest.getAttemptNo() + 1;
        String key = reuse != null ? reuse.getUniqueKey()
                : "DB" + (trigger == Trigger.SCHEDULED ? "S" : "M") + loanId + "-" + attemptNo;
        if (!UNIQUE_KEY.matcher(key).matches() || !NARRATION.matcher(NARRATION_TEXT).matches()) {
            throw new BusinessException("DEBIT_REFERENCE_INVALID", "A debit reference could not be formed");
        }

        MandateDebit row = reuse != null ? reuse : new MandateDebit();
        row.setMandateId(mandate.getId());
        row.setLoanId(loanId);
        row.setUniqueKey(key);
        row.setAttemptNo(attemptNo);
        row.setAmountPaise(amount);
        row.setSettlementDate(settle);
        row.setStatus(Status.SCHEDULED);
        row.setDigioStatus(null);
        row.setFailureCode(null);
        row.setFailureReason(null);
        row.setDpdAtAttempt(dpd);
        row.setTriggeredBy(trigger);
        row.setActorId(actorId);
        try {
            row = debits.saveAndFlush(row); // the index refuses a concurrent second in-flight row right here
        } catch (DataIntegrityViolationException race) {
            throw new BusinessException("DEBIT_IN_FLIGHT", "A debit is already in progress for this loan");
        }
        return new Prepared(row, new DebitRequest(mandate.getUmrn(), amount, settle, key,
                corporateAccountNumber.trim(), NARRATION_TEXT), false);
    }

    /**
     * After a failed/timed-out create: read the plan back by key. Found means it landed (adopt, never
     * re-present). Empty or unreadable means the outcome is unknown: park the row as FAILED_TECHNICAL, whose
     * next presentation recovers by this same key first.
     */
    private DebitPlan recover(MandateDebit row) {
        Optional<DebitPlan> found;
        try {
            found = port.findDebitByRef(row.getUniqueKey());
        } catch (RuntimeException lookupFailure) {
            log.warn("Debit recovery lookup failed debitId={} exception={}", row.getId(),
                    lookupFailure.getClass().getSimpleName());
            found = Optional.empty();
        }
        if (found.isPresent()) {
            return found.get();
        }
        row.setStatus(Status.FAILED_TECHNICAL);
        tx.execute(s -> debits.save(row));
        throw new BusinessException("DEBIT_UNCONFIRMED", "The debit could not be confirmed. Please try again shortly.");
    }

    private Optional<DebitPlan> lookup(String key) {
        try {
            return port.findDebitByRef(key);
        } catch (RuntimeException e) {
            log.warn("Debit lookup failed key={} exception={}", key, e.getClass().getSimpleName());
            throw new BusinessException("DEBIT_UNAVAILABLE", "The debit status could not be read just now");
        }
    }

    private static void apply(MandateDebit row, DebitPlan plan) {
        row.setDigioPlanId(plan.planId());
        if (plan.txnId() != null) {
            row.setDigioTxnId(plan.txnId());
        }
        row.setDigioStatus(plan.status());
        if (plan.scheduledSettlementDate() != null) {
            row.setSettlementDate(plan.scheduledSettlementDate());
        }
        if (row.getStatus() == Status.FAILED_TECHNICAL) {
            row.setStatus(Status.SCHEDULED); // it exists at the provider after all
        }
    }

    // ---- outcomes ----------------------------------------------------------------------------------------

    /** docs/digio/09-state-mapping.md section 3. Unknown statuses are quarantined (raw value kept), never guessed. */
    private void applyOutcome(MandateDebit row, DebitOutcome o) {
        String raw = o.status() == null ? "" : o.status().trim().toUpperCase(Locale.ROOT);
        row.setDigioStatus(o.status());
        Status mapped = switch (raw) {
            case "NEW", "READY_TO_SEND" -> Status.SCHEDULED;
            case "SUBMITTED_TO_BANK", "ACCEPTED_SPO_BANK", "PAYMENT_DELAYED", "PAYMENT_AUTHENTICATED" -> Status.IN_PROGRESS;
            case "PAYMENT_SUCCESS" -> Status.SUCCEEDED;
            case "PAYMENT_FAILED", "REJECT_SPO_BANK" -> Status.FAILED;
            case "SKIPPED" -> Status.SKIPPED;
            case "CANCELLED" -> Status.CANCELLED;
            case "DIGIO_TECHNICAL_ERROR" -> Status.FAILED_TECHNICAL;
            default -> null;
        };
        if (mapped == null) {
            log.warn("Unrecognised debit status debitId={} status={}", row.getId(), raw);
        } else if (mapped == Status.SUCCEEDED) {
            creditSuccess(row, o);
            return;
        } else if (mapped == Status.FAILED) {
            row.setStatus(Status.FAILED);
            row.setFailureCode(o.failureCode());
            String reason = o.failureReason();
            row.setFailureReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)));
        } else if (!(mapped == Status.SCHEDULED && row.getStatus() == Status.IN_PROGRESS)) { // monotonic
            row.setStatus(mapped);
        }
        tx.execute(s -> debits.save(row));
    }

    /**
     * Success becomes a VERIFIED payment through the existing seam, with the UTR as txnRef so a replay dedupes.
     * Runs outside our transaction; if the credit throws anything but LOAN_SETTLED the row stays in flight and
     * the next reconcile retries (harmlessly: the txnRef lookup/dedupe).
     */
    private void creditSuccess(MandateDebit row, DebitOutcome o) {
        long amount = o.amountPaise() > 0 ? o.amountPaise() : row.getAmountPaise();
        String txnRef = o.utr() != null && !o.utr().isBlank() ? o.utr() : row.getDigioTxnId();
        LocalDate today = LocalDate.now(IST);
        LocalDate paidOn = row.getSettlementDate() == null || row.getSettlementDate().isAfter(today)
                ? today : row.getSettlementDate();
        Long paymentId = null;
        boolean overpayment = false;
        var existing = payments.findFirstByLoanIdAndTxnRef(row.getLoanId(), txnRef);
        if (existing.isPresent()) {
            paymentId = existing.get().getId();
        } else {
            try {
                paymentId = loans.creditCollectionPayment(row.getLoanId(), amount, txnRef,
                        "NACH:" + row.getDigioTxnId(), paidOn);
            } catch (BusinessException e) {
                if (!"LOAN_SETTLED".equals(e.getCode())) {
                    throw e;
                }
                // The loan closed while the debit was in flight: money we no longer owe. Parked for refund.
                overpayment = true;
                log.warn("NACH debit landed on a settled loan debitId={} loanId={}", row.getId(), row.getLoanId());
            }
        }
        row.setStatus(Status.SUCCEEDED);
        row.setUtr(o.utr());
        row.setPaymentId(paymentId);
        row.setOverpayment(overpayment);
        tx.execute(s -> debits.save(row));
    }

    private DebitView view(MandateDebit d) {
        return new DebitView(d.getId(), d.getLoanId(), d.getStatus(), d.getAmountPaise(), d.getSettlementDate(),
                d.getAttemptNo(), d.getTriggeredBy(), d.getUtr(), d.isOverpayment(), eligibleForRepresentation(d));
    }
}
