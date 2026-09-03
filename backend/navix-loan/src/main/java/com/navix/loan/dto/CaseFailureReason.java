package com.navix.loan.dto;

/**
 * Why one application has no usable credit decision yet — the normalized answer behind the
 * Customers page's Failure column.
 *
 * <p><b>This enum carries machine names only.</b> Every string a human reads — the cell label, the
 * explanation, the remedy — lives once in {@code frontend/src/components/staff/case-failure.ts},
 * the same split {@code BureauState} / {@code bureau-state.ts} already uses. Nothing here may ever
 * carry a provider's error text, an exception message or an HTTP body: those stay in the ADMIN
 * provider workbench, which is the only surface allowed to show a raw exchange.
 *
 * <p><b>Declaration order is the precedence order.</b> {@code VerificationFailureService} walks these
 * top to bottom and takes the first match, so an application with two problems reports the one that
 * has to be solved first. Reordering changes behaviour — the ordering is: someone else's data in our
 * file, then something an admin can type, then something a person must do, then a provider or plan
 * problem, then answers that are not failures at all.
 *
 * <p>Existence here does not imply a bug. {@link #BUREAU_NO_RECORD} is the system working correctly
 * (40% of the September 2026 pending queue), and {@link #PAN_INVALID} is three vendors independently
 * agreeing a PAN was never issued — a data-quality signal, not a defect.
 */
public enum CaseFailureReason {

    /** The returned report's own PAN/DOB did not match the verified KYC identity. */
    BUREAU_IDENTITY_MISMATCH(Severity.BLOCKED),

    /** No name on file, so the bureau request cannot be built. The intake never asks for one — it
     *  arrives from the PAN record — so a PAN outage, or a PAN that passes while returning no name,
     *  lands here. An admin types the name and re-runs. */
    BUREAU_MISSING_NAME(Severity.ADMIN_FIXABLE),

    /** No date of birth on file; the bureau requires one. */
    BUREAU_MISSING_DOB(Severity.ADMIN_FIXABLE),

    /** The bureau holds a real report behind a knowledge-based security question the borrower has
     *  not answered. */
    BUREAU_KBA_PENDING(Severity.ACTIONABLE),

    /** As above, but the borrower gave up. The question can be refreshed and re-asked. */
    BUREAU_KBA_SKIPPED(Severity.ACTIONABLE),

    /** The bureau rejected the request for a reason other than a missing name — some detail we sent
     *  was wrong or incomplete. */
    BUREAU_PROVIDER_REJECTED_REQUEST(Severity.ADMIN_FIXABLE),

    /** A real report was returned and then discarded by a defect since fixed: the pull recorded
     *  "no record" while the stored response still holds the tradelines. Re-running now keeps it. */
    BUREAU_REPORT_DISCARDED(Severity.ACTIONABLE),

    /** The bureau has records for this identity only under mobile numbers we never sent. Releasing
     *  them needs a vendor endpoint we do not implement — no re-run can resolve it. */
    BUREAU_MASKED_MOBILE_FOLLOW_UP(Severity.BLOCKED),

    /** The provider account could not pay for the call. */
    BUREAU_PROVIDER_NO_BALANCE(Severity.OPS),

    /** The borrower's file is larger than our bureau plan returns. */
    BUREAU_PROVIDER_PLAN_LIMIT(Severity.OPS),

    /** Every bureau we tried failed to respond. Usually transient. */
    BUREAU_PROVIDER_UNAVAILABLE(Severity.ACTIONABLE),

    /** The PAN could not be verified — every provider we tried failed. */
    PAN_UNVERIFIED(Severity.ACTIONABLE),

    /** Every provider independently reported this PAN as not issued. Not a system fault. */
    PAN_INVALID(Severity.BLOCKED),

    /** The borrower has not completed the bureau consent step, so no pull has run. */
    BUREAU_CONSENT_PENDING(Severity.INFO),

    /** Consent is given but no bureau row exists at all. */
    BUREAU_NOT_RUN(Severity.ACTIONABLE),

    /** A full, readable report with no usable score. Correct and complete — underwrite from the
     *  account history. */
    BUREAU_NO_SCORE(Severity.INFO),

    /** The bureau genuinely has no credit history for this identity. The system working. */
    BUREAU_NO_RECORD(Severity.INFO),

    /** Nothing is wrong; the file simply has not been assigned to a credit reviewer. */
    AWAITING_ASSIGNMENT(Severity.INFO),

    /** No outstanding problem. Rendered as an empty cell. */
    NONE(Severity.INFO);

    /**
     * How a reader should treat the row. Drives the badge tone and, deliberately, whether the
     * re-run action is offered at all.
     */
    public enum Severity {
        /** Needs a person, and re-running cannot help. */
        BLOCKED,
        /** An admin can correct customer data here and re-run. */
        ADMIN_FIXABLE,
        /** Re-running, or asking the borrower, can resolve it. */
        ACTIONABLE,
        /** An account or vendor-plan problem — outside this application. */
        OPS,
        /** Informational; nothing to do. */
        INFO
    }

    private final Severity severity;

    CaseFailureReason(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }

    /**
     * Should staff be offered a "re-run the credit check" action for this reason?
     *
     * <p>Deliberately narrow. Every bureau pull is billable and is a real credit inquiry on a real
     * person's file, so re-running is offered only where it can plausibly change the answer. It is
     * withheld from genuine no-hits, an invalid PAN, a plan limit and the masked-mobile case, where
     * a re-run could only ever spend money to arrive at the same place.
     */
    public boolean retryable() {
        return severity == Severity.ADMIN_FIXABLE || severity == Severity.ACTIONABLE;
    }
}
