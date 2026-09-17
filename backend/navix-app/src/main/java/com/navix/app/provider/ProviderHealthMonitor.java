package com.navix.app.provider;

import com.navix.common.notification.event.ProviderHealthEvent;
import com.navix.verification.support.ProviderHealth;
import com.navix.verification.support.ProviderHealthSink;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns two silent provider conditions into notifications an ADMIN actually sees.
 *
 * <p>Both were invisible before. A prepaid account at zero balance produced one ERROR log line per
 * failed call and nothing else — production sat that way for 11.5 hours in Aug 2026 and 106
 * applications lost their bureau pull. A capability failing outright produced nothing at all: Aadhaar
 * eSign was rejected by Signzy 156 times out of 156 across four weeks, and because {@code esignInit}
 * catches the failure and offers the drawn-signature fallback, every screen looked normal.
 *
 * <p>Two detectors, deliberately different in kind:
 * <ul>
 *   <li><b>Balance</b> — pushed, from inside the call that saw the provider's exhausted-balance code
 *       ({@link ProviderHealthSink}). Immediate, because there is nothing to infer.</li>
 *   <li><b>Capability down</b> — pulled, by an hourly sweep of {@code provider_api_execution}. Not an
 *       in-memory counter: that resets on every deploy, and on a capability serving five calls a day
 *       it would never reach a sane threshold before being reset. The table already holds the truth
 *       and is restart-proof.</li>
 * </ul>
 *
 * <p>Alerts are rate-limited per key so a day-long outage produces four notifications rather than
 * twenty-four. Publishing is best-effort throughout: an alert that fails must never surface as a
 * verification failure, which is why {@link ProviderHealth} swallows and why the sweep catches.
 *
 * <p><b>Single instance only</b>, like every scheduler here — see {@code SchedulingConfig}.
 */
@Component
public class ProviderHealthMonitor implements ProviderHealthSink {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthMonitor.class);

    /** How far back the outage sweep looks. */
    private static final Duration WINDOW = Duration.ofHours(24);

    /**
     * Minimum live calls in the window before a 0 % success rate means anything. Ten is low enough to
     * catch eSign (roughly five a day, so it trips on the second day of an outage) and high enough
     * that one borrower retrying three times in a bad minute does not page anybody.
     */
    private static final int MIN_CALLS = 10;

    /** One alert per key per this long. A 24-hour outage is worth four notifications, not twenty-four. */
    private static final Duration ALERT_INTERVAL = Duration.ofHours(6);

    private final ApplicationEventPublisher events;
    private final ProviderApiExecutionRepository repository;
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();

    public ProviderHealthMonitor(ApplicationEventPublisher events,
                                 ProviderApiExecutionRepository repository) {
        this.events = events;
        this.repository = repository;
    }

    @PostConstruct
    void install() {
        ProviderHealth.setSink(this);
    }

    /**
     * Called from inside a provider call. Must be cheap and must not throw — a map lookup and an
     * event publish, both O(1); {@link ProviderHealth} is the backstop for the "must not throw" half.
     */
    @Override
    public void balanceExhausted(String provider, String endpoint) {
        if (!shouldAlert("BALANCE:" + provider)) {
            return;
        }
        log.error("PROVIDER_HEALTH balance exhausted provider={} endpoint={} — alerting administrators",
                provider, endpoint);
        events.publishEvent(new ProviderHealthEvent(ProviderHealthEvent.Kind.BALANCE_EXHAUSTED,
                provider, null, endpoint, 0, Instant.now()));
    }

    /** Hourly at :15 (override with {@code navix.provider-health.cron}). */
    @Scheduled(cron = "${navix.provider-health.cron:0 15 * * * *}")
    public void sweep() {
        Instant since = Instant.now().minus(WINDOW);
        List<ProviderApiExecutionRepository.HealthRow> rows;
        try {
            rows = repository.healthSince(since);
        } catch (RuntimeException queryFailure) {
            log.warn("PROVIDER_HEALTH sweep could not read the audit table: {}", queryFailure.toString());
            return;
        }
        for (ProviderApiExecutionRepository.HealthRow row : rows) {
            if (row.getCalls() < MIN_CALLS || row.getSuccesses() > 0) {
                continue;
            }
            String key = "DOWN:" + row.getProvider() + ":" + row.getOperation();
            if (!shouldAlert(key)) {
                continue;
            }
            log.error("PROVIDER_HEALTH capability down provider={} operation={} calls={} successes=0 "
                    + "window={}h", row.getProvider(), row.getOperation(), row.getCalls(),
                    WINDOW.toHours());
            try {
                events.publishEvent(new ProviderHealthEvent(ProviderHealthEvent.Kind.CAPABILITY_DOWN,
                        row.getProvider(), row.getOperation(), null, (int) row.getCalls(), since));
            } catch (RuntimeException publishFailure) {
                // One capability's alert must not sink the rest of the sweep.
                log.warn("PROVIDER_HEALTH could not publish {}: {}", key, publishFailure.toString());
            }
        }
    }

    /** True at most once per {@link #ALERT_INTERVAL} per key. */
    private boolean shouldAlert(String key) {
        Instant now = Instant.now();
        Instant previous = lastAlert.get(key);
        if (previous != null && previous.isAfter(now.minus(ALERT_INTERVAL))) {
            return false;
        }
        lastAlert.put(key, now);
        return true;
    }
}
