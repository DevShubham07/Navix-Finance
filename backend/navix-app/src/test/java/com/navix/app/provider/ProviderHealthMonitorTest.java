package com.navix.app.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.notification.event.ProviderHealthEvent;
import com.navix.verification.support.ProviderHealth;
import com.navix.verification.support.ProviderHealthSink;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The two provider conditions that used to be invisible, and the guards that keep alerting about them
 * from becoming its own incident.
 *
 * <p>Both detectors exist because of a specific outage that nobody saw. Production sat at zero Digitap
 * balance for 11.5 hours in Aug 2026 and 106 applications lost their bureau pull behind a generic HTTP
 * 402 — the only trace was an ERROR log line nobody was reading. Separately, Aadhaar eSign was rejected
 * by Signzy 156 times out of 156 across four weeks and raised nothing, because every failure was caught
 * and quietly degraded into a drawn-signature fallback; every screen looked normal for a month.
 */
@ExtendWith(MockitoExtension.class)
class ProviderHealthMonitorTest {

    @Mock private ApplicationEventPublisher events;
    @Mock private ProviderApiExecutionRepository repository;

    /**
     * {@link ProviderHealth} holds its sink in a static field, so a monitor installed by one test would
     * keep receiving alerts from every other test sharing this JVM. Put the no-op back.
     */
    @AfterEach
    void restoreTheNoopSink() {
        ProviderHealth.setSink(ProviderHealthSink.NOOP);
    }

    /** A fresh monitor per test: the rate limiter is per-instance state and must not leak between them. */
    private ProviderHealthMonitor monitor() {
        return new ProviderHealthMonitor(events, repository);
    }

    /** The {@code healthSince} projection is a Spring Data interface; this is the whole of it. */
    private record Row(String provider, String operation, long calls, long successes)
            implements ProviderApiExecutionRepository.HealthRow {

        @Override
        public String getProvider() {
            return provider;
        }

        @Override
        public String getOperation() {
            return operation;
        }

        @Override
        public long getCalls() {
            return calls;
        }

        @Override
        public long getSuccesses() {
            return successes;
        }
    }

    private ProviderHealthEvent onlyPublishedEvent() {
        ArgumentCaptor<ProviderHealthEvent> captor = ArgumentCaptor.forClass(ProviderHealthEvent.class);
        verify(events).publishEvent(captor.capture());
        return captor.getValue();
    }

    private List<ProviderHealthEvent> publishedEvents(int expected) {
        ArgumentCaptor<ProviderHealthEvent> captor = ArgumentCaptor.forClass(ProviderHealthEvent.class);
        verify(events, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    /**
     * A prepaid account at zero is the one provider condition nothing can infer its way out of — the
     * call that saw the exhausted-balance code already knows. So it is pushed immediately, with the
     * provider and the endpoint that revealed it, rather than waiting for the hourly sweep.
     *
     * <p>Before this, the same condition produced a single ERROR log line per failed call and nothing
     * else: 11.5 hours and 106 lost bureau pulls in Aug 2026, behind a generic HTTP 402.
     */
    @Test
    void balanceExhaustedPublishesAnEvent() {
        monitor().balanceExhausted("DIGITAP", "https://api.digitap.ai/credit_analytics/request");

        ProviderHealthEvent event = onlyPublishedEvent();
        assertThat(event.kind()).isEqualTo(ProviderHealthEvent.Kind.BALANCE_EXHAUSTED);
        assertThat(event.provider()).isEqualTo("DIGITAP");
        assertThat(event.endpoint()).isEqualTo("https://api.digitap.ai/credit_analytics/request");
    }

    /**
     * A prepaid account at zero fails EVERY call, so without the rate limit one outage would publish
     * thousands of notifications — an inbox nobody can read, from the one alert that most needed
     * reading. The 11.5-hour Aug 2026 outage is worth a handful of notifications, not one per borrower.
     *
     * <p>The key is the provider alone, deliberately: the balance is account-wide, so the second alert
     * is suppressed even though it arrived from a different endpoint.
     */
    @Test
    void theSameBalanceAlertIsNotRepeatedWithinTheRateLimitWindow() {
        ProviderHealthMonitor monitor = monitor();

        monitor.balanceExhausted("DIGITAP", "https://api.digitap.ai/credit_analytics/request");
        monitor.balanceExhausted("DIGITAP", "https://api.digitap.ai/v1/kyc/uan");

        verify(events, times(1)).publishEvent(any(ProviderHealthEvent.class));
    }

    /**
     * Rate limiting is per provider, not global. Two vendors are two separate top-ups by two separate
     * people; suppressing the second because the first just alerted would hide a whole outage.
     */
    @Test
    void differentProvidersAlertIndependently() {
        ProviderHealthMonitor monitor = monitor();

        monitor.balanceExhausted("DIGITAP", "https://api.digitap.ai/credit_analytics/request");
        monitor.balanceExhausted("SIGNZY", "https://api.signzy.app/api/v3/pan/verify");

        assertThat(publishedEvents(2))
                .extracting(ProviderHealthEvent::provider)
                .containsExactlyInAnyOrder("DIGITAP", "SIGNZY");
    }

    /**
     * The eSign case, exactly: a capability that has not succeeded once in 24 hours across enough live
     * calls to mean something. Signzy rejected 156 of 156 eSign contracts over four weeks and raised
     * nothing, because each individual failure was caught and degraded into a drawn-signature fallback.
     * No single call could have detected that; only the shape of the whole window can.
     */
    @Test
    void theSweepFlagsACapabilityWithNoSuccessesIn24Hours() {
        when(repository.healthSince(any(Instant.class)))
                .thenReturn(List.of(new Row("SIGNZY", "ESIGN", 23, 0)));

        monitor().sweep();

        ProviderHealthEvent event = onlyPublishedEvent();
        assertThat(event.kind()).isEqualTo(ProviderHealthEvent.Kind.CAPABILITY_DOWN);
        assertThat(event.provider()).isEqualTo("SIGNZY");
        assertThat(event.operation()).isEqualTo("ESIGN");
        assertThat(event.calls()).isEqualTo(23);
    }

    /**
     * Below the call threshold a 0 % success rate means nothing, so it must page nobody: one borrower
     * retrying three times in a bad minute is not an outage. The threshold is what keeps this alert
     * credible enough to be acted on when it does fire.
     */
    @Test
    void theSweepIgnoresACapabilityBelowTheCallThreshold() {
        when(repository.healthSince(any(Instant.class)))
                .thenReturn(List.of(new Row("DIGITAP", "PENNY_DROP", 9, 0)));

        monitor().sweep();

        verify(events, never()).publishEvent(any(ProviderHealthEvent.class));
    }

    /**
     * One success is proof the capability is reachable and configured, so it is not down — whatever the
     * failure rate. A degraded-but-working provider is a different conversation, and this alert is
     * deliberately the blunt one: nothing is getting through at all.
     */
    @Test
    void theSweepIgnoresACapabilityWithAnySuccess() {
        when(repository.healthSince(any(Instant.class)))
                .thenReturn(List.of(new Row("DIGITAP", "BUREAU", 50, 1)));

        monitor().sweep();

        verify(events, never()).publishEvent(any(ProviderHealthEvent.class));
    }

    /**
     * Raising an alert must never be able to break anything — including the rest of the alerting.
     *
     * <p>The listener behind these events sends notifications, which means it can fail for reasons that
     * have nothing to do with providers. If one capability's publish could abort the loop, a broken
     * SIGNZY alert would silently swallow the DIGITAP one behind it, and the sweep that exists to make
     * outages visible would itself go quiet — the exact failure mode it was built to end.
     */
    @Test
    void aPublisherThatThrowsNeverBreaksTheSweep() {
        when(repository.healthSince(any(Instant.class))).thenReturn(List.of(
                new Row("SIGNZY", "ESIGN", 40, 0),
                new Row("DIGITAP", "EMPLOYMENT", 60, 0)));
        doThrow(new IllegalStateException("notification dispatcher down"))
                .doNothing()
                .when(events).publishEvent(any(ProviderHealthEvent.class));

        assertThatCode(() -> monitor().sweep()).doesNotThrowAnyException();

        assertThat(publishedEvents(2))
                .extracting(ProviderHealthEvent::provider)
                .containsExactly("SIGNZY", "DIGITAP");
    }

    /**
     * The sweep reads the audit table, and a database that cannot answer is not an outage worth
     * crashing a scheduler thread over. It runs hourly; the next run will try again.
     */
    @Test
    void aQueryFailureIsSwallowed() {
        when(repository.healthSince(any(Instant.class)))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timeout"));

        assertThatCode(() -> monitor().sweep()).doesNotThrowAnyException();

        verifyNoInteractions(events);
    }

    /**
     * The balance detector is only ever reached through a static holder: fifteen provider clients call
     * {@code ProviderHealth.balanceExhausted}, in a module that knows nothing about Spring events, and
     * {@code navix-app} installs the real sink at startup. If that installation ever stops happening the
     * NOOP stays in place and every balance alert vanishes with no error anywhere — which is precisely
     * the silence this whole class was built to end.
     */
    @Test
    void installingTheSinkMakesProviderHealthRouteToTheMonitor() {
        ProviderHealthMonitor monitor = monitor();
        monitor.install();

        ProviderHealth.balanceExhausted("DIGITAP", "https://api.digitap.ai/credit_analytics/request");

        assertThat(ProviderHealth.sink()).isSameAs(monitor);
        ProviderHealthEvent event = onlyPublishedEvent();
        assertThat(event.kind()).isEqualTo(ProviderHealthEvent.Kind.BALANCE_EXHAUSTED);
        assertThat(event.provider()).isEqualTo("DIGITAP");
    }
}
