package com.navix.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.ProviderCallContext;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The hourly sweep that re-runs EPFO/UAN employment checks a VENDOR OUTAGE parked.
 *
 * <p>The backlog these tests stand in for is real: a 27-hour Digitap prepaid-balance outage on
 * 2026-09-08 stranded 173 employment checks showing "unavailable" forever, and an intermittent EPFO
 * "source is busy" accounts for another 145. Nothing ever came back for any of them, because a parked
 * check never blocked the borrower and so nothing ever asked again.
 *
 * <p>What is pinned here is the sweep's behaviour, not its economics — but the economics are why it is
 * allowed to be this eager: Digitap bills only a RESOLVED UAN record (result code 101), so a failed
 * retry is free and the one outcome we pay for is the one we want.
 */
@ExtendWith(MockitoExtension.class)
class EmploymentRetrySchedulerTest {

    private static final String FLAG = "employment-auto-retry";

    @Mock private ApplicationVerificationService verification;
    @Mock private FeatureFlagService featureFlags;

    /**
     * The sweep binds a thread-local actor and provider-call context per application. A test that
     * leaves either bound would hand it to whatever test the JUnit runner puts on this thread next.
     */
    @AfterEach
    void clearThreadState() {
        ProviderCallContext.clear();
        ActorContext.clear();
    }

    private EmploymentRetryScheduler scheduler() {
        return new EmploymentRetryScheduler(verification, featureFlags);
    }

    private void flagOn() {
        when(featureFlags.isEnabled(FLAG, true)).thenReturn(true);
    }

    private static ApplicationVerification parked(long applicationId, String derived) {
        ApplicationVerification row = new ApplicationVerification();
        row.setApplicationId(applicationId);
        row.setCheckType("EMPLOYMENT");
        row.setStatus("REVIEW");
        row.setDerived(derived);
        return row;
    }

    /** What the provider-error path writes: the marker the sweep reads back off the re-run. */
    private static StepResult stillFailing() {
        return new StepResult("EMPLOYMENT", "REVIEW", "Employment check unavailable",
                Map.of("providerError", true, "providerErrorCode", "SOURCE_BUSY"));
    }

    private static StepResult resolved() {
        return new StepResult("EMPLOYMENT", "REVIEW", "Employer differs from the declared one",
                Map.of("uan", "100200300400", "employerName", "ACME PRIVATE LIMITED"));
    }

    /**
     * The whole point of the sweep: every parked check is asked again, and asked with {@code force}.
     *
     * <p>{@code force} is load-bearing. Most of the stranded rows sit in REVIEW, and
     * {@code verifyEmployment} deliberately reuses an already-resolved REVIEW record rather than buying
     * it a second time — so a sweep that passed {@code false} would re-read its own parked row and
     * "succeed" without ever reaching Digitap, quietly draining the backlog into nothing.
     *
     * <p>The counter is the other half: an outage that never lifts must not be retried forever, so a
     * re-run that is still a provider error bumps {@code retryCount} towards
     * {@code MAX_EMPLOYMENT_RETRIES}, after which the query stops offering the row and a human takes it.
     */
    @Test
    void retriesEachCandidateWithForceAndBumpsTheCounter() {
        flagOn();
        when(verification.employmentRetryCandidates(anyInt())).thenReturn(List.of(
                parked(101L, "{\"providerError\":true,\"retryCount\":2}"),
                parked(102L, null)));
        when(verification.verifyEmployment(anyLong(), anyBoolean())).thenReturn(stillFailing());

        scheduler().retryParkedEmploymentChecks();

        verify(verification).verifyEmployment(101L, true);
        verify(verification).verifyEmployment(102L, true);
        // 2 attempts already recorded → this one is the 3rd; a row with no derived starts at 1.
        verify(verification).markEmploymentRetried(101L, 3);
        verify(verification).markEmploymentRetried(102L, 1);
    }

    /**
     * A retry that came back with an answer is done, and the counter is left exactly where it was.
     *
     * <p>Bumping it anyway would be a slow leak: a borrower whose check resolved on the fourth sweep
     * would carry a retryCount of 5 into any later re-park and be shut out of the retry window for a
     * reason that has nothing to do with the vendor.
     */
    @Test
    void aResolvedRetryDoesNotBumpTheCounter() {
        flagOn();
        when(verification.employmentRetryCandidates(anyInt()))
                .thenReturn(List.of(parked(201L, "{\"providerError\":true,\"retryCount\":1}")));
        when(verification.verifyEmployment(anyLong(), anyBoolean())).thenReturn(resolved());

        scheduler().retryParkedEmploymentChecks();

        verify(verification).verifyEmployment(201L, true);
        verify(verification, never()).markEmploymentRetried(anyLong(), anyInt());
    }

    /**
     * One bad application must never sink the rest of the sweep.
     *
     * <p>The backlog is a queue of files that already went wrong once — a deleted profile, a malformed
     * stored payload, a vendor answering HTML. If the first of 318 could abort the run, the hourly
     * sweep would make no progress at all and the outage would look permanent from the outside.
     */
    @Test
    void oneFailingApplicationDoesNotStopTheSweep() {
        flagOn();
        when(verification.employmentRetryCandidates(anyInt()))
                .thenReturn(List.of(parked(301L, null), parked(302L, null)));
        when(verification.verifyEmployment(eq(301L), anyBoolean()))
                .thenThrow(new IllegalStateException("customer profile missing"));
        when(verification.verifyEmployment(eq(302L), anyBoolean())).thenReturn(resolved());

        scheduler().retryParkedEmploymentChecks();

        verify(verification).verifyEmployment(302L, true);
    }

    /**
     * Nothing binds an actor or a provider-call context on a scheduler thread, and the pool reuses
     * threads — so the sweep sets both per application and clears them in a {@code finally}.
     *
     * <p>Both halves are audit-visible. Without the actor the application events would be attributed to
     * whoever last ran on this thread; without {@code source = RETRY} these calls would be filed as LIVE
     * borrower traffic, which quietly poisons two things — an operator reading a Digitap traffic spike,
     * and {@code ProviderHealthMonitor}, whose outage sweep counts LIVE rows precisely so that re-runs
     * of calls that already failed once cannot drag a healthy capability's success rate to zero.
     */
    @Test
    void setsAndClearsTheSystemActorAndProviderContext() {
        flagOn();
        when(verification.employmentRetryCandidates(anyInt())).thenReturn(List.of(parked(401L, null)));
        when(verification.verifyEmployment(anyLong(), anyBoolean())).thenAnswer(invocation -> {
            assertThat(ActorContext.get()).isEqualTo(CurrentActor.SYSTEM);
            assertThat(ProviderCallContext.source()).isEqualTo("RETRY");
            assertThat(ProviderCallContext.applicationId()).isEqualTo(401L);
            assertThat(ProviderCallContext.checkType()).isEqualTo("EMPLOYMENT");
            return resolved();
        });

        // Bound first so the "cleared" assertions below prove the finally ran, rather than merely
        // observing a thread that was never touched.
        ActorContext.set(new CurrentActor("77", "Asha Menon", "CREDIT_HEAD"));

        scheduler().retryParkedEmploymentChecks();

        assertThat(ActorContext.get()).isEqualTo(CurrentActor.SYSTEM);
        assertThat(ProviderCallContext.applicationId()).isNull();
        assertThat(ProviderCallContext.checkType()).isNull();
        assertThat(ProviderCallContext.source()).isEqualTo(ProviderCallContext.LIVE);
    }

    /**
     * The flag is a kill switch, so it has to cut the sweep off before it reads anything.
     *
     * <p>A re-run spends money and generates vendor traffic; the reason it is gated at all is that an
     * operator needs to stop it during an incident without waiting for a deploy. A flag checked after
     * the work list is loaded, or after the first call, would not be that.
     */
    @Test
    void theFlagIsAKillSwitch() {
        when(featureFlags.isEnabled(FLAG, true)).thenReturn(false);

        scheduler().retryParkedEmploymentChecks();

        verifyNoInteractions(verification);
    }

    /** The steady state: once the backlog is drained the hourly sweep is a single query and nothing else. */
    @Test
    void anEmptyBacklogDoesNothing() {
        flagOn();
        when(verification.employmentRetryCandidates(anyInt())).thenReturn(List.of());

        scheduler().retryParkedEmploymentChecks();

        verify(verification, never()).verifyEmployment(anyLong(), anyBoolean());
        verify(verification, never()).markEmploymentRetried(anyLong(), anyInt());
    }
}
