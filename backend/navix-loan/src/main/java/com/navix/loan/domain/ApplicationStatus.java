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
 *  DRAFT → KYC_PENDING → CREDIT_EXEC_PENDING → SANCTIONED → DISBURSEMENT_PENDING →
 *  DISBURSED → ACTIVE → {CLOSED | OVERDUE → DEFAULTED → WRITTEN_OFF}
 *  branches: KYC_REJECTED, REJECTED, CANCELLED, DISBURSEMENT_FAILED → (retry) DISBURSEMENT_PENDING
 * </pre>
 *
 * <p>The credit maker-checker (CREDIT_EXEC_APPROVED → CREDIT_HEAD_PENDING → CREDIT_HEAD_APPROVED)
 * and the reborrow review (REVIEW_PENDING) were retired in V45: the Credit Executive's sanction is
 * final. The accountant disbursement hop (ACCOUNTANT_PENDING) was retired in V48: the Disbursement
 * Head's transaction id <em>is</em> the validation. Those values stay in the enum so historical rows
 * and the {@code application_event} trail still deserialize; nothing transitions to them.
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
    /** @deprecated Retired with the manual reborrow review (V45); kept for historical rows. */
    @Deprecated
    REVIEW_PENDING,
    CREDIT_EXEC_PENDING,
    /** @deprecated Retired with the credit maker-checker (V45); kept for historical rows. */
    @Deprecated
    CREDIT_EXEC_APPROVED,
    /** @deprecated Retired with the credit maker-checker (V45); kept for historical rows. */
    @Deprecated
    CREDIT_HEAD_PENDING,
    /** @deprecated Retired with the credit maker-checker (V45); kept for historical rows. */
    @Deprecated
    CREDIT_HEAD_APPROVED,
    /**
     * Credit sanctioned an amount + repayment date, and the borrower is walking the post-approval
     * journey (DigiLocker → references → selfie → eSign → disbursal account). Completing it routes
     * to {@link #DISBURSEMENT_PENDING}. A sanction never expires (revamp.md decision 41).
     */
    SANCTIONED,
    DISBURSEMENT_PENDING,
    /** @deprecated Retired with the accountant disbursement hop (V48); historical rows only. */
    @Deprecated
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
        // REJECTED is reachable straight from DRAFT for the engine rules that turn an applicant away
        // during intake — today the self-employed gate, from Phase 4 also past delinquency (V44).
        TRANSITIONS.put(DRAFT, EnumSet.of(KYC_PENDING, PRE_APPROVED, REVIEW_PENDING, REJECTED, CANCELLED));
        // The Credit Head assigns a submitted file straight to an executive (V45); the intermediate
        // KYC_APPROVED stop survives for the credit team's optional explicit KYC clearance.
        TRANSITIONS.put(KYC_PENDING, EnumSet.of(CREDIT_EXEC_PENDING, KYC_APPROVED, KYC_REJECTED, REJECTED, CANCELLED));
        TRANSITIONS.put(KYC_APPROVED, EnumSet.of(CREDIT_EXEC_PENDING, SANCTIONED, REJECTED, CANCELLED));
        TRANSITIONS.put(KYC_REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        // Retired with the manual reborrow review (V45) — historical rows only.
        TRANSITIONS.put(REVIEW_PENDING, EnumSet.of(PRE_APPROVED, REJECTED, CANCELLED));
        // Pre-approved returning borrower: re-applying carries the prior sanction (→ SANCTIONED, where
        // they re-walk the short offer journey). DISBURSEMENT_PENDING remains legal for rows minted
        // before V45 that already skipped straight to the Disbursement Head.
        TRANSITIONS.put(PRE_APPROVED, EnumSet.of(SANCTIONED, DISBURSEMENT_PENDING, CANCELLED));
        // The Credit Executive's decision is FINAL (decision 27) — sanction or reject, no Head
        // counter-approval. CREDIT_EXEC_APPROVED / CREDIT_HEAD_* are off the live path.
        TRANSITIONS.put(CREDIT_EXEC_PENDING, EnumSet.of(SANCTIONED, REJECTED, CANCELLED));
        TRANSITIONS.put(CREDIT_EXEC_APPROVED, EnumSet.of(CREDIT_HEAD_PENDING));
        TRANSITIONS.put(CREDIT_HEAD_PENDING, EnumSet.of(CREDIT_HEAD_APPROVED, REJECTED));
        TRANSITIONS.put(CREDIT_HEAD_APPROVED, EnumSet.of(DISBURSEMENT_PENDING));
        // The borrower accepts the offer (Phase 3: amount → eSign → disbursal account) → disbursement.
        TRANSITIONS.put(SANCTIONED, EnumSet.of(DISBURSEMENT_PENDING, REJECTED, CANCELLED));
        // The Disbursement Head releases DIRECTLY, always with a transaction id (V47/V48,
        // decision 42): DISBURSEMENT_PENDING → DISBURSED → ACTIVE. The transaction id IS the
        // validation — there is no second desk behind the Head, so nothing reaches
        // ACCOUNTANT_PENDING any more and a retry returns to the Head who owns the release.
        TRANSITIONS.put(DISBURSEMENT_PENDING, EnumSet.of(DISBURSED, REJECTED));
        TRANSITIONS.put(ACCOUNTANT_PENDING, EnumSet.noneOf(ApplicationStatus.class));
        TRANSITIONS.put(DISBURSEMENT_FAILED, EnumSet.of(DISBURSEMENT_PENDING, CANCELLED));
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
