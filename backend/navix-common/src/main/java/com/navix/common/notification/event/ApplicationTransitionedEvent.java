package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published once per application state-machine transition from
 * {@code ApplicationFlowService.logEvent}, right after the audit row is written. The async listener
 * has no {@code ActorContext} and no transaction, so <b>every datum is carried inline</b>.
 * {@code fromStatus}/{@code toStatus} are {@code ApplicationStatus} names (the enum lives in
 * navix-loan, which navix-common must not depend on — hence Strings).
 */
public record ApplicationTransitionedEvent(
        Long applicationId,
        Long applicantId,
        Long loanId,
        String fromStatus,
        String toStatus,
        String action,
        Long assignedExecutiveId,
        String actorId,
        String actorRole,
        Instant at) {
}
