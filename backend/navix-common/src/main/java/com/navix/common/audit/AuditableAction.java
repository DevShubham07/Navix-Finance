package com.navix.common.audit;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Immutable record of a single human action in the maker-checker workflow:
 * who did what, to which resource, and when. Used to build the segregation-of-duties
 * audit trail (Credit Executive review vs Credit Head approval vs Disbursement Head
 * release vs Accountant transfer confirmation).
 *
 * TODO: persist as an append-only audit log and expose via an audit query API.
 */
@Data
@Builder
public class AuditableAction {

    /** Username / id of the actor who performed the action. */
    private String actor;

    /** Role the actor was acting as (e.g. CREDIT_EXECUTIVE, CREDIT_HEAD). */
    private String role;

    /** The action performed (e.g. REVIEWED, APPROVED, REJECTED, RELEASED, CONFIRMED). */
    private String action;

    /** Type of the affected resource (e.g. LOAN_APPLICATION). */
    private String resourceType;

    /** Identifier of the affected resource. */
    private String resourceId;

    /** Optional free-text remarks captured at the time of the action. */
    private String remarks;

    /** When the action occurred. */
    private Instant occurredAt;
}
