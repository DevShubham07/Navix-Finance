package com.navix.loan.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical loan-application lifecycle (dfd.md §8). A single application aggregate carries one
 * {@code status} through this state machine — no stage-skipping. Each enum value declares the
 * states it may legally transition to; {@link #canTransitionTo} enforces it server-side.
 *
 * <pre>
 *  DRAFT → KYC_PENDING → KYC_APPROVED → CREDIT_EXEC_PENDING → CREDIT_EXEC_APPROVED →
 *  CREDIT_HEAD_PENDING → CREDIT_HEAD_APPROVED → DISBURSEMENT_PENDING → ACCOUNTANT_PENDING →
 *  DISBURSED → ACTIVE → {CLOSED | OVERDUE → DEFAULTED → WRITTEN_OFF}
 *  branches: KYC_REJECTED, REJECTED, CANCELLED, DISBURSEMENT_FAILED → (retry) ACCOUNTANT_PENDING
 * </pre>
 */
public enum ApplicationStatus {
    DRAFT,
    KYC_PENDING,
    KYC_APPROVED,
    KYC_REJECTED,
    // Returning-borrower (reborrow) states: a repeat borrower reuses their saved KYC profile and
    // skips the fresh KYC + credit gates. PRE_APPROVED = cleared, awaiting amount → on apply routes
    // straight to DISBURSEMENT_PENDING. REVIEW_PENDING = a borrower with past delinquency held for a
    // KYC-approver re-review before they may proceed (→ PRE_APPROVED on approve).
    PRE_APPROVED,
    REVIEW_PENDING,
    CREDIT_EXEC_PENDING,
    CREDIT_EXEC_APPROVED,
    CREDIT_HEAD_PENDING,
    CREDIT_HEAD_APPROVED,
    DISBURSEMENT_PENDING,
    ACCOUNTANT_PENDING,
    DISBURSEMENT_FAILED,
    DISBURSED,
    ACTIVE,
    OVERDUE,
    DEFAULTED,
    CLOSED,
    WRITTEN_OFF,
    REJECTED,
    CANCELLED;

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS = new java.util.EnumMap<>(ApplicationStatus.class);

    static {
        // A fresh draft normally goes to KYC; a reborrow draft is routed to PRE_APPROVED (clean
        // history) or REVIEW_PENDING (past delinquency) by ApplicationFlowService.reborrow.
        TRANSITIONS.put(DRAFT, EnumSet.of(KYC_PENDING, PRE_APPROVED, REVIEW_PENDING, CANCELLED));
        TRANSITIONS.put(KYC_PENDING, EnumSet.of(KYC_APPROVED, KYC_REJECTED));
        // A KYC-approved applied application normally enters the credit maker-checker (CREDIT_EXEC_PENDING).
        // The KYC approver may also clear the credit gate directly in the instant-loan model — routing
        // straight to DISBURSEMENT_PENDING (or REJECTED) — while disbursement stays with the Disb. Head.
        TRANSITIONS.put(KYC_APPROVED, EnumSet.of(CREDIT_EXEC_PENDING, DISBURSEMENT_PENDING, REJECTED, CANCELLED));
        TRANSITIONS.put(KYC_REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        // Reborrow review gate: a KYC approver clears the borrower (→ PRE_APPROVED) or rejects.
        TRANSITIONS.put(REVIEW_PENDING, EnumSet.of(PRE_APPROVED, REJECTED, CANCELLED));
        // Pre-approved returning borrower: applying for an amount routes straight to disbursement
        // (skips the credit maker-checker — a deliberate relaxation for repeat borrowers).
        TRANSITIONS.put(PRE_APPROVED, EnumSet.of(DISBURSEMENT_PENDING, CANCELLED));
        TRANSITIONS.put(CREDIT_EXEC_PENDING, EnumSet.of(CREDIT_EXEC_APPROVED, REJECTED));
        TRANSITIONS.put(CREDIT_EXEC_APPROVED, EnumSet.of(CREDIT_HEAD_PENDING));
        TRANSITIONS.put(CREDIT_HEAD_PENDING, EnumSet.of(CREDIT_HEAD_APPROVED, REJECTED));
        TRANSITIONS.put(CREDIT_HEAD_APPROVED, EnumSet.of(DISBURSEMENT_PENDING));
        // The Disbursement Head may either hand off to the accountant, or — when they supply a
        // transaction id — finalize the release directly (DISBURSED, then auto → ACTIVE).
        TRANSITIONS.put(DISBURSEMENT_PENDING, EnumSet.of(ACCOUNTANT_PENDING, DISBURSED, REJECTED));
        TRANSITIONS.put(ACCOUNTANT_PENDING, EnumSet.of(DISBURSED, DISBURSEMENT_FAILED));
        TRANSITIONS.put(DISBURSEMENT_FAILED, EnumSet.of(ACCOUNTANT_PENDING, CANCELLED));
        TRANSITIONS.put(DISBURSED, EnumSet.of(ACTIVE));
        TRANSITIONS.put(ACTIVE, EnumSet.of(CLOSED, OVERDUE));
        TRANSITIONS.put(OVERDUE, EnumSet.of(CLOSED, DEFAULTED));
        TRANSITIONS.put(DEFAULTED, EnumSet.of(OVERDUE, WRITTEN_OFF));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(ApplicationStatus.class));
        TRANSITIONS.put(WRITTEN_OFF, EnumSet.noneOf(ApplicationStatus.class));
        TRANSITIONS.put(REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(ApplicationStatus.class));
    }

    /** Whether this status may legally transition to {@code next} (dfd.md §8). */
    public boolean canTransitionTo(ApplicationStatus next) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(ApplicationStatus.class)).contains(next);
    }

    /** Terminal states have no outgoing transitions. */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(ApplicationStatus.class)).isEmpty();
    }
}
