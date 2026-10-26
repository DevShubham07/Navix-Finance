package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.loan.DebitDirectory;
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
import java.util.List;
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
    private static final Set<Status> UNSUCCESSFUL = EnumSet.of(Status.FAILED, Status.FAILED_TECHNICAL);
    private static final Set<String> LIVE_LOAN = Set.of("ACTIVE", "OVERDUE", "IN_COLLECTIONS");
    private static final Pattern NARRATION = Pattern.compile("^[0-9A-Za-z& .]{1,20}$");
    private static final Pattern UNIQUE_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");
    static final String NARRATION_TEXT = "DhanBoost repayment";
    /** Keeps the loan-keyed advisory lock out of the customer-keyed one MandateService takes. */
    static final long LOCK_NAMESPACE = 1L << 40;
    /** A row with no provider ids is closed locally only once its last write is older than any create call. */
    static final java.time.Duration LOCAL_CLOSE_MIN_AGE = java.time.Duration.ofMinutes(15);
    /**
     * Reject reasons that may NEVER be re-presented: the mandate or the account is gone, the payer is
     * gone, or the reference collided — another attempt cannot succeed, and each attempt is a real
     * provider call. Matched as substrings against the failure code AND the failure reason, upper-cased
     * with punctuation folded to {@code _}, because Digio's {@code failure_code} vocabulary is still
     * unobserved and may well be numeric NPCI codes — in which case the human-readable reason is the
     * only text there is to match on.
     *
     * <p><b>Everything else is re-presented, including a code we have never seen.</b> That is the
     * deliberate inversion of what this started as. An allowlist of three guessed strings failed
     * closed, which sounds conservative and is not: against numeric codes it matches nothing, so the
     * DPD 3/5/9 presentations never fire, the sweep logs look healthy, and the loan is silently never
     * collected after its first attempt. Re-presenting a dead mandate costs one rejected presentation;
     * never re-presenting costs the whole debt. {@link #MAX_ATTEMPTS}, {@link #MAX_PRESENTATIONS_PER_ROW}
     * and the usable-mandate check in {@link #prepare} are what bound the downside.
     *
     * <p>{@code DUPLICATE} is terminal for the opposite reason: a duplicate-reference reject means the
     * original presentation did land, so stopping is what prevents a second debit.
     */
    private static final List<String> TERMINAL_MARKERS = List.of(
            "ACCOUNT_CLOSED", "ACCOUNT_BLOCKED", "ACCOUNT_FROZEN", "ACCOUNT_INOPERATIVE",
            "ACCOUNT_TRANSFERRED", "ACCOUNT_DORMANT", "DORMANT", "NO_SUCH_ACCOUNT",
            "INVALID_ACCOUNT", "ACCOUNT_DOES_NOT_EXIST", "ACCOUNT_NOT_FOUND",
            "DEBIT_NOT_ALLOWED", "DEBIT_BLOCKED", "DEBIT_STOPPED", "PAYMENT_STOPPED",
            "MANDATE_CANCELLED", "MANDATE_REVOKED", "MANDATE_EXPIRED", "MANDATE_INVALID",
            "MANDATE_NOT_FOUND", "MANDATE_NOT_REGISTERED", "NO_MANDATE", "UMRN_NOT_FOUND",
            "DECEASED", "KYC_PENDING", "AMOUNT_EXCEEDS", "LIMIT_EXCEEDED", "DUPLICATE");
    // UNVERIFIED: our reading of the NPCI return-reason vocabulary (docs/digio/06 section 5), not values
    // observed from Digio. Review this list against the first real failure_code; unlike the allowlist it
    // replaced, a gap here costs one pointless re-presentation rather than every retry on the loan.

    /** DPD 1/3/5/9 is the schedule, so four presentations is the ceiling per loan. NPCI's own caps are invisible to us. */
    static final int MAX_ATTEMPTS = 4;
    /**
     * Ceiling on presentations of ONE row. The FAILED_TECHNICAL recovery path keeps the original
     * unique_key and attempt_no on purpose (that key is the double-charge backstop), so neither the
     * terminal-reject check nor MAX_ATTEMPTS can advance there: without this, a collections officer could
     * press "debit" without limit, each press a real provider POST. Three = the first presentation plus
     * two recoveries; beyond that the outcome is not unknown, the integration is broken.
     */
    static final int MAX_PRESENTATIONS_PER_ROW = 3;

    private final MandateDebitRepository debits;
    private final MandateService mandateService;
    private final LoanDirectory loans;
    private final RepaymentService repayments;
    private final MandatePort port;
    private final PaymentRepository payments;
    private final TransactionTemplate tx;

    /** Opened per call: the credit runs in its own transaction so a caught LOAN_SETTLED cannot poison ours. */
    private TransactionTemplate creditTx() {
        TransactionTemplate t = new TransactionTemplate(tx.getTransactionManager());
        t.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** The lender's settlement account; blank means debits are not configured and we refuse to present. */
    @Value("${navix.digio.corporate-account-number:}")
    private String corporateAccountNumber;

    /**
     * False when the lender's settlement account is unset, in which case every presentation is refused with
     * {@code DEBIT_NOT_CONFIGURED}. Callers check it once per pass so a misconfiguration is one loud error
     * rather than a per-loan warning on a silently inert sweep.
     */
    public boolean configured() {
        return corporateAccountNumber != null && !corporateAccountNumber.isBlank();
    }

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
        MandateDebit probe = debits.findById(debitId)
                .orElseThrow(() -> new BusinessException("DEBIT_NOT_FOUND", "Debit not found"));
        // Same loan lock as prepare: the sweep and a manual reconcile must not both credit one outcome
        // (recordPayment throws LOAN_SETTLED before its own txnRef dedupe, so that is no backstop).
        return tx.execute(s -> {
            debits.lockLoan(LOCK_NAMESPACE | probe.getLoanId());
            MandateDebit row = debits.findById(debitId).orElse(probe); // re-read under the lock
            return reconcileLocked(row);
        });
    }

    private DebitView reconcileLocked(MandateDebit row) {
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

    /**
     * True for a failed debit that is worth presenting again: anything but a {@link #TERMINAL_MARKERS}
     * reason. An unrecognised code is re-presentable — see that field for why that is the safe default.
     * Only {@code FAILED} qualifies; {@code FAILED_TECHNICAL} goes down the recover-by-key path instead.
     */
    public boolean eligibleForRepresentation(MandateDebit d) {
        if (d == null || d.getStatus() != Status.FAILED) {
            return false;
        }
        String text = normalise(d.getFailureCode()) + "_" + normalise(d.getFailureReason());
        return TERMINAL_MARKERS.stream().noneMatch(text::contains);
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    /**
     * Cheap pre-check for the sweep: does this loan have a mandate worth presenting against at all?
     *
     * <p>Delegates to the SAME {@code usableMandateForLoan} predicate {@link #prepare} enforces — it is
     * deliberately not a second copy of the rule, because a money-affecting rule in two places drifts.
     * {@code prepare} remains the authority; this exists only so the daily sweep stops calling
     * {@code present()} on every mandate-less overdue loan and burying real failures under one
     * {@code NO_USABLE_MANDATE} warning per loan per day.
     */
    public boolean hasUsableMandate(Long loanId) {
        return mandateService.usableMandateForLoan(loanId)
                .filter(m -> m.getUmrn() != null && !m.getUmrn().isBlank())
                .isPresent();
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
            // ...but bound it. The key and attempt_no stay frozen here, so present_count is the only cap.
            if (reuse.getPresentCount() >= MAX_PRESENTATIONS_PER_ROW) {
                throw new BusinessException("DEBIT_ATTEMPTS_EXHAUSTED",
                        "The maximum number of debit attempts has been reached");
            }
        }

        // Terminal means terminal, for every caller: a FAILED row whose code NPCI does not allow re-presenting
        // (or an unknown code) can never be followed by another attempt, and the schedule has a ceiling.
        if (reuse == null && latest != null) {
            if (latest.getStatus() == Status.FAILED && !eligibleForRepresentation(latest)) {
                throw new BusinessException("DEBIT_NOT_REPRESENTABLE", "The last debit failed in a way that cannot be retried");
            }
            // Only fruitless attempts consume the cap; a SUCCEEDED part payment, a CANCELLED or a SKIPPED row does not.
            if (debits.countByLoanIdAndStatusIn(loanId, UNSUCCESSFUL) >= MAX_ATTEMPTS) {
                throw new BusinessException("DEBIT_ATTEMPTS_EXHAUSTED", "The maximum number of debit attempts has been reached");
            }
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
        if (!configured()) {
            throw new BusinessException("DEBIT_NOT_CONFIGURED", "Debits are not configured");
        }

        // T-1 cutoff: what we request today settles tomorrow at the earliest. Size the debit to what is owed on
        // that day, not today: penalty keeps accruing until settlement, and sizing to today left a 1-day residue.
        LocalDate settle = today.plusDays(1);
        long outstanding = repayments.outstandingAsOf(loanId, settle);
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
        if (mandate.getMaximumAmountPaise() <= 0 || amount > mandate.getMaximumAmountPaise()) {
            throw new BusinessException("AMOUNT_EXCEEDS_MANDATE", "The amount is above the mandate limit");
        }
        if (amount > Integer.MAX_VALUE) { // Digio's amount_in_paise is int32
            throw new BusinessException("AMOUNT_TOO_LARGE", "The amount is too large for a single debit");
        }
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

        // A reused FAILED_TECHNICAL row overwrites its actor and trigger below: the audit trail of the original
        // attempt is deliberately traded for key stability (the identical unique_key is the double-charge backstop).
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
        row.setPresentCount(reuse != null ? reuse.getPresentCount() + 1 : 1);
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
                final String ref = txnRef;
                paymentId = creditTx().execute(s -> loans.creditCollectionPayment(row.getLoanId(), amount, ref,
                        "NACH:" + row.getDigioTxnId(), paidOn));
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

    /**
     * Cancel one in-flight debit at the provider (Digio transaction action {@code cancel}).
     *
     * <p>Asks the provider first: a debit stuck as SCHEDULED in our table may already have settled or failed
     * at the bank, and a success must be credited, not cancelled. Only a debit the provider still holds as
     * pending is cancelled — and Digio allows that only before the bank has picked it up, so a refusal is a
     * normal answer ({@code DEBIT_CANCEL_REFUSED}): the outcome then arrives through reconcile as usual.
     *
     * <p>A row with no provider transaction and no plan was never filed, so it is closed locally. A plan
     * without a transaction id cannot be cancelled through this API and is refused rather than guessed at.
     */
    public DebitView cancel(Long loanId, Long debitId, String actorLabel) {
        MandateDebit probe = debits.findById(debitId)
                .filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new BusinessException("DEBIT_NOT_FOUND", "Debit not found"));
        if (!IN_FLIGHT.contains(probe.getStatus())) {
            throw new BusinessException("DEBIT_NOT_IN_FLIGHT", "This debit has already finished; there is nothing to cancel");
        }
        try {
            DebitView now = reconcile(debitId); // commits whatever the bank says, success credited
            if (!IN_FLIGHT.contains(now.status())) {
                return now;
            }
        } catch (BusinessException unreadable) {
            // A status read that fails must not also block the cancel: a stuck read is often why it is stuck.
            log.warn("Debit status read before cancel failed debitId={} code={}", debitId, unreadable.getCode());
        }
        return tx.execute(s -> {
            debits.lockLoan(LOCK_NAMESPACE | probe.getLoanId());
            MandateDebit row = debits.findById(debitId).orElse(probe);
            if (!IN_FLIGHT.contains(row.getStatus())) {
                return view(row);
            }
            if (row.getDigioTxnId() == null) {
                // Closing a row locally is only safe once the provider has confirmed it holds nothing under our
                // key — and present() makes its provider call outside any lock, so a young row may be mid-create
                // right now. Fail closed on both: a lookup error or a found plan refuses, and so does a row too
                // new for its create call to have finished.
                Optional<DebitPlan> found = lookup(row.getUniqueKey()); // throws DEBIT_UNAVAILABLE on error
                if (found.isPresent()) {
                    apply(row, found.get());
                    debits.save(row);
                }
                if (row.getDigioTxnId() == null) {
                    // Measured from the row's LAST write, not its creation: prepare() re-presents a reused
                    // FAILED_TECHNICAL row under its old id (saveAndFlush bumps updated_at), so an old row can be
                    // mid-create too. The reconcile sweep also touches updated_at, which only makes this stricter.
                    // If a create does land after a local close, present()'s final save writes the provider ids
                    // and SCHEDULED back over CANCELLED — the safe direction: the real debit stays tracked.
                    java.time.Instant lastWrite = row.getUpdatedAt() != null
                            && (row.getCreatedAt() == null || row.getUpdatedAt().isAfter(row.getCreatedAt()))
                            ? row.getUpdatedAt() : row.getCreatedAt();
                    boolean young = lastWrite == null
                            || lastWrite.isAfter(java.time.Instant.now().minus(LOCAL_CLOSE_MIN_AGE));
                    if (found.isPresent() || row.getDigioPlanId() != null || young) {
                        throw new BusinessException("DEBIT_CANCEL_UNAVAILABLE",
                                "The bank has not issued a transaction for this debit yet. Check status again shortly.");
                    }
                    markCancelled(row, actorLabel, "never filed with the provider");
                    return view(row);
                }
            }
            boolean accepted;
            try {
                accepted = port.cancelDebit(row.getDigioTxnId());
            } catch (RuntimeException e) {
                log.warn("Debit cancel failed debitId={} exception={}", debitId, e.getClass().getSimpleName());
                throw new BusinessException("DEBIT_UNAVAILABLE", "The debit could not be cancelled just now. Try again.");
            }
            if (!accepted) {
                throw new BusinessException("DEBIT_CANCEL_REFUSED",
                        "The bank has already started processing this debit, so it can no longer be cancelled. "
                                + "Use Check status for the outcome.");
            }
            markCancelled(row, actorLabel, "cancelled at the provider");
            log.info("Debit cancelled debitId={} loanId={} by={}", debitId, row.getLoanId(), actorLabel);
            return view(row);
        });
    }

    /** Reconcile one debit of {@code loanId} on demand (staff "Check status"). */
    public DebitView reconcileForLoan(Long loanId, Long debitId) {
        debits.findById(debitId).filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new BusinessException("DEBIT_NOT_FOUND", "Debit not found"));
        return reconcile(debitId);
    }

    private void markCancelled(MandateDebit row, String actorLabel, String how) {
        row.setStatus(Status.CANCELLED);
        String reason = "Cancelled by " + (actorLabel == null ? "staff" : actorLabel) + " (" + how + ")";
        row.setFailureReason(reason.substring(0, Math.min(reason.length(), 500)));
        debits.save(row);
    }

    // ---- reads -------------------------------------------------------------------------------------

    /** Every presentation on one loan, newest attempt first. (No @Transactional here — see the class note.) */
    public List<DebitDirectory.DebitRecord> history(Long loanId) {
        return debits.findByLoanIdOrderByAttemptNoDesc(loanId).stream().map(this::record).toList();
    }

    /** The refund register: money taken from borrowers who no longer owed it, newest first. */
    public List<DebitDirectory.DebitRecord> overpayments() {
        return debits.findByOverpaymentTrueOrderByIdDesc().stream().map(this::record).toList();
    }

    private DebitDirectory.DebitRecord record(MandateDebit d) {
        return new DebitDirectory.DebitRecord(d.getId(), d.getLoanId(), d.getStatus().name(), d.getAmountPaise(),
                d.getSettlementDate(), d.getAttemptNo(), d.getTriggeredBy() == null ? null : d.getTriggeredBy().name(),
                d.getUtr(), d.isOverpayment(), eligibleForRepresentation(d), d.getCreatedAt());
    }

    private DebitView view(MandateDebit d) {
        return new DebitView(d.getId(), d.getLoanId(), d.getStatus(), d.getAmountPaise(), d.getSettlementDate(),
                d.getAttemptNo(), d.getTriggeredBy(), d.getUtr(), d.isOverpayment(), eligibleForRepresentation(d));
    }
}
