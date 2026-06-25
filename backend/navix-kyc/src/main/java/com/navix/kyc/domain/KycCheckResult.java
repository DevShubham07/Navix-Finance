package com.navix.kyc.domain;

/**
 * Outcome of a single {@code KycCheck}. Persisted on the entity's String {@code result} field
 * via {@link #name()}; the enum gives the API a validated, closed set of values.
 */
public enum KycCheckResult {
    PASS,
    FAIL,
    REVIEW
}
