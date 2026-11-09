package com.navix.scheduler;

import com.navix.collections.service.DpdCalculator;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.MandateDebit.Status;
import com.navix.loan.entity.MandateDebit.Trigger;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.service.DebitService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.springframework.data.domain.PageRequest;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily NACH debit sweep: reconciles debits already in flight, then presents new ones to overdue
 * loans at DPD 1, 3, 5 and 9 (DPD counted from the due date). EVERY presentation moves real money
 * and the provider has no idempotency key, so every guard lives in {@link DebitService}; this class
 * only decides WHICH loans to offer it, gates on the {@code digio-debit} flag, and isolates failures.
 *
 * <p>Deliberately not {@code @Transactional}: {@code present}/{@code reconcile} put the provider call
 * between two commits. <b>Single instance only</b>, like every scheduler here (see SchedulingConfig).
 */
@Component
public class MandateDebitScheduler {

    private static final Logger log = LoggerFactory.getLogger(MandateDebitScheduler.class);

    private static final String FLAG = "digio-debit";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int[] ATTEMPT_DPD = {1, 3, 5, 9};
    /** reconcile holds a DB connection + advisory lock across a provider read; keep a pass bounded. */
    private static final int RECONCILE_BATCH = 50;

    private final DebitService debitService;
    private final MandateDebitRepository debits;
    private final LoanDirectory loans;
    private final DpdCalculator dpd;
    private final FeatureFlagService featureFlags;

    public MandateDebitScheduler(DebitService debitService, MandateDebitRepository debits, LoanDirectory loans,
                                 DpdCalculator dpd, FeatureFlagService featureFlags) {
        this.debitService = debitService;
        this.debits = debits;
        this.loans = loans;
        this.dpd = dpd;
        this.featureFlags = featureFlags;
    }

    /**
     * 10:00 IST (zone pinned, since ECS runs UTC = 04:30 UTC). A debit requested on day T settles on
     * T+1 at the earliest, and the request must beat the late-night T-1 cutoff. Running in the morning
     * leaves the whole working day to notice a failed sweep and fix or present manually before that
     * cutoff; a late-evening run would have no margin for either. Override with {@code navix.digio.debit-cron}.
     */
    @Scheduled(cron = "${navix.digio.debit-cron:0 0 10 * * *}", zone = "Asia/Kolkata")
    public void sweep() {
        if (!featureFlags.isEnabled(FLAG, false)) {
            return;
        }
        LocalDate today = LocalDate.now(IST);
        reconcileInFlight(today);
        presentDue(today);
    }

    private void reconcileInFlight(LocalDate today) {
        // Bounded and ordered in the query: least-recently-attempted first. A row that cannot be resolved is
        // touched (below) so it rotates to the back and the queue drains instead of starving.
        // ponytail: this repurposes updated_at as "last attempted" for open debits, not "last state change".
        // Upgrade path if that muddies anything: a dedicated last_reconciled_at column (needs a migration).
        List<MandateDebit> due = debits.findDueForReconcile(
                Set.of(Status.SCHEDULED, Status.IN_PROGRESS, Status.FAILED_TECHNICAL), today,
                PageRequest.of(0, RECONCILE_BATCH));
        for (MandateDebit d : due) {
            boolean resolved = false;
            try {
                DebitService.DebitView v = debitService.reconcile(d.getId());
                resolved = v != null && v.status() != Status.SCHEDULED && v.status() != Status.IN_PROGRESS
                        && v.status() != Status.FAILED_TECHNICAL;
            } catch (RuntimeException e) {
                log.warn("Debit reconcile skipped debitId={}: {}", d.getId(), e.getMessage());
            }
            if (!resolved) {
                try {
                    debits.touch(d.getId(), Instant.now());
                } catch (RuntimeException e) {
                    log.warn("Debit touch failed debitId={}: {}", d.getId(), e.getMessage());
                }
            }
        }
    }

    private void presentDue(LocalDate today) {
        // One ERROR for the pass, not a warn per loan: without the settlement account EVERY loan fails
        // DEBIT_NOT_CONFIGURED, and a silently inert collection sweep is exactly the failure mode the
        // eSign landmine in CLAUDE.md exists to warn about.
        if (!debitService.configured()) {
            log.error("Debit sweep stopped: NAVIX_DIGIO_CORPORATE_ACCOUNT_NUMBER "
                    + "(navix.digio.corporate-account-number) is unset, so no debit can be presented");
            return;
        }
        int presented = 0;
        for (LoanSummary loan : loans.listCollectible(today)) {
            try {
                if (shouldPresent(loan, today)) {
                    debitService.present(loan.loanId(), null, Trigger.SCHEDULED, null);
                    presented++;
                }
            } catch (RuntimeException e) {
                log.warn("Debit presentation skipped loanId={}: {}", loan.loanId(), e.getMessage());
            }
        }
        log.info("Debit sweep: {} presented", presented);
    }

    /**
     * Due when the loan has had fewer attempts than schedule days reached so far (DPD 1/3/5/9), so a
     * missed day is caught up on the next run, but never past DPD 9 and never twice for one slot.
     * A previous rejection NPCI does not allow re-presenting is terminal; {@code present} also refuses it.
     */
    private boolean shouldPresent(LoanSummary loan, LocalDate today) {
        int d = dpd.daysPastDue(loan.dueDate(), today);
        if (d < ATTEMPT_DPD[0] || d > ATTEMPT_DPD[ATTEMPT_DPD.length - 1]) {
            return false;
        }
        // Most overdue loans hold no mandate, and present() would refuse each one with NO_USABLE_MANDATE
        // — caught, logged, and repeated every single day. That buries a genuine failure under noise, so
        // skip them here. This is a FILTER, not the guard: DebitService.prepare still refuses on its own,
        // and this calls the same predicate rather than restating it.
        if (!debitService.hasUsableMandate(loan.loanId())) {
            return false;
        }
        int slotsReached = 0;
        for (int s : ATTEMPT_DPD) {
            if (d >= s) {
                slotsReached++;
            }
        }
        MandateDebit latest = debits.findFirstByLoanIdOrderByAttemptNoDesc(loan.loanId()).orElse(null);
        if (latest == null) {
            return true;
        }
        if (latest.getStatus() == Status.SCHEDULED || latest.getStatus() == Status.IN_PROGRESS
                || (latest.getStatus() == Status.FAILED && !debitService.eligibleForRepresentation(latest))) {
            return false;
        }
        return latest.getAttemptNo() < slotsReached;
    }
}
