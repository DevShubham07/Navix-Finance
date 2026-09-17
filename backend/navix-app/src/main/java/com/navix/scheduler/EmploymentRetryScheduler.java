package com.navix.scheduler;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.ProviderCallContext;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.service.ApplicationVerificationService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-runs EPFO/UAN employment checks that a VENDOR OUTAGE parked.
 *
 * <p>These checks never blocked a borrower — an unavailable provider records REVIEW and the journey
 * continues — but nothing ever came back for them either. A 27-hour Digitap prepaid-balance outage on
 * 2026-09-08 stranded 173 checks that way, and an intermittent EPFO "source is busy" accounts for
 * another 145 spread across the audit window: 318 applications whose reviewer sees "Employment check
 * unavailable" where an employer name should be, indefinitely.
 *
 * <p>Retrying is cheap in exactly the way that matters: Digitap bills only a RESOLVED record (result
 * code 101), so every failed attempt here is free and the one outcome we are paying for is the one we
 * want. The guards that keep it honest live in
 * {@link ApplicationVerificationService#employmentRetryCandidates} — vendor-side error codes only,
 * exponential spacing, and undecided applications only.
 *
 * <p><b>Single instance only</b>, like every other scheduler here: there is no distributed lock, so a
 * multi-instance deploy would run this sweep several times over. See {@code SchedulingConfig}.
 */
@Component
public class EmploymentRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmploymentRetryScheduler.class);

    /** Kill switch (V72). A re-run can spend money, so it needs an off switch that needs no deploy. */
    private static final String FLAG = "employment-auto-retry";

    /**
     * Applications touched per sweep. Hourly × 50 drains the 318-row backlog in under a working day
     * without turning a recovered vendor into a thundering herd.
     */
    private static final int BATCH = 50;

    /**
     * Marks these calls in the provider audit trail so an operator reading a spike of Digitap traffic
     * can tell a sweep from live borrower journeys. {@code provider_api_execution.source} is
     * {@code varchar(10)}.
     */
    private static final String SOURCE = "RETRY";

    private final ApplicationVerificationService verification;
    private final FeatureFlagService featureFlags;

    public EmploymentRetryScheduler(ApplicationVerificationService verification,
                                    FeatureFlagService featureFlags) {
        this.verification = verification;
        this.featureFlags = featureFlags;
    }

    /** Runs hourly at :30 (override with {@code navix.employment-retry.cron}). */
    @Scheduled(cron = "${navix.employment-retry.cron:0 30 * * * *}")
    public void retryParkedEmploymentChecks() {
        if (!featureFlags.isEnabled(FLAG, true)) {
            return;
        }
        List<ApplicationVerification> parked = verification.employmentRetryCandidates(BATCH);
        if (parked.isEmpty()) {
            return;
        }
        int resolved = 0;
        int stillFailing = 0;
        for (ApplicationVerification row : parked) {
            Long appId = row.getApplicationId();
            try {
                // Nothing binds an actor or a call context on a scheduler thread, and the pool reuses
                // threads — so both are set explicitly and cleared in the finally. Without the actor
                // the audit would attribute these to whoever last ran on this thread; without the call
                // context the provider rows would be filed as LIVE borrower traffic.
                ActorContext.set(CurrentActor.SYSTEM);
                ProviderCallContext.setSource(SOURCE);
                ProviderCallContext.setApplicationId(appId);
                ProviderCallContext.setCheckType("EMPLOYMENT");
                int attempt = retryCount(row) + 1;
                var result = verification.verifyEmployment(appId, true);
                if (isStillAProviderError(result.derived())) {
                    verification.markEmploymentRetried(appId, attempt);
                    stillFailing++;
                } else {
                    resolved++;
                }
            } catch (RuntimeException ex) {
                // One bad application must never sink the rest of the sweep.
                log.warn("Employment-retry sweep skipped application {}: {}", appId, ex.getMessage());
            } finally {
                ProviderCallContext.clear();
                ActorContext.clear();
            }
        }
        log.info("Employment-retry sweep: {} parked checks re-run, {} resolved, {} still failing",
                parked.size(), resolved, stillFailing);
    }

    private static boolean isStillAProviderError(Map<String, Object> derived) {
        return derived != null && Boolean.TRUE.equals(derived.get("providerError"));
    }

    private static int retryCount(ApplicationVerification row) {
        String derived = row.getDerived();
        if (derived == null) {
            return 0;
        }
        // Read off the raw JSON rather than parsing it: the only thing needed here is the count the
        // query already filtered on, and the service owns the parsed shape.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"retryCount\"\\s*:\\s*(\\d+)").matcher(derived);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
