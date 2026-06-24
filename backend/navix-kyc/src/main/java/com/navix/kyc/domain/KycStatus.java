package com.navix.kyc.domain;

/**
 * Overall outcome of a {@code KycCase}. Persisted on the entity's String {@code status} field
 * via {@link #name()}; the enum gives the service/API a validated, closed set of values.
 *
 * <ul>
 *   <li>{@code PENDING} — case opened, no terminal signal yet</li>
 *   <li>{@code IN_REVIEW} — at least one check needs manual review (and none failed)</li>
 *   <li>{@code APPROVED} — approver decision: passed</li>
 *   <li>{@code REJECTED} — a check failed or the approver rejected</li>
 * </ul>
 */
public enum KycStatus {
    PENDING,
    IN_REVIEW,
    APPROVED,
    REJECTED
}
