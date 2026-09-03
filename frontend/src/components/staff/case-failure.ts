import type { BadgeVariant } from "@/components/ui/badge";

/**
 * Single source of truth for every human-readable string behind the Customers page's Failure
 * column — the cell label, the one-sentence detail and the remedy line — exactly as
 * `bureau-state.ts` is for `BureauState`. The backend's `CaseFailureReason` enum (mirrored below)
 * carries machine names only and MUST NOT gain provider error text, an exception message or an
 * HTTP body: those stay in the ADMIN provider workbench, which is the only surface allowed to show
 * a raw exchange.
 *
 * Keep this list in the same order as `com.navix.loan.dto.CaseFailureReason` and add a new entry
 * here whenever a value is added there — nothing renders for a reason missing from `REASONS`.
 */
export type CaseFailureReason =
  | "BUREAU_IDENTITY_MISMATCH"
  | "BUREAU_MISSING_NAME"
  | "BUREAU_MISSING_DOB"
  | "BUREAU_KBA_PENDING"
  | "BUREAU_KBA_SKIPPED"
  | "BUREAU_PROVIDER_REJECTED_REQUEST"
  | "BUREAU_REPORT_DISCARDED"
  | "BUREAU_MASKED_MOBILE_FOLLOW_UP"
  | "BUREAU_PROVIDER_NO_BALANCE"
  | "BUREAU_PROVIDER_PLAN_LIMIT"
  | "BUREAU_PROVIDER_UNAVAILABLE"
  | "PAN_UNVERIFIED"
  | "PAN_INVALID"
  | "BUREAU_CONSENT_PENDING"
  | "BUREAU_NOT_RUN"
  | "BUREAU_NO_SCORE"
  | "BUREAU_NO_RECORD"
  | "AWAITING_ASSIGNMENT"
  | "NONE";

/** Mirrors `CaseFailureReason.Severity` — drives the badge tone and whether a re-run is offered. */
export type CaseFailureSeverity = "BLOCKED" | "ADMIN_FIXABLE" | "ACTIONABLE" | "OPS" | "INFO";

export interface CaseFailureCopy {
  /** Short Failure-column cell text. */
  label: string;
  /** One sentence explaining what happened, for the failure detail dialog. */
  detail: string;
  /** What staff should do about it — a verb-first line, also for the detail dialog. */
  remedy: string;
}

/**
 * The lookup: one label/detail/remedy triple per `CaseFailureReason`. `NONE` carries empty
 * strings — callers render an empty Failure cell for it (see `isNoFailure`) rather than reading
 * these fields directly.
 */
export const CASE_FAILURE_INFO: Record<CaseFailureReason, CaseFailureCopy> = {
  BUREAU_IDENTITY_MISMATCH: {
    label: "Report identity mismatch",
    detail: "The returned report's PAN or date of birth does not match this borrower.",
    remedy: "Manual review before any decision",
  },
  BUREAU_MISSING_NAME: {
    label: "Name missing",
    detail: "We have no name for this borrower, so the bureau request cannot be made.",
    remedy: "Add the name, then re-run the credit check",
  },
  BUREAU_MISSING_DOB: {
    label: "Date of birth missing",
    detail: "The bureau requires a date of birth and none is on file.",
    remedy: "Add the date of birth, then re-run",
  },
  BUREAU_KBA_PENDING: {
    label: "Security question unanswered",
    detail: "The bureau holds a report but wants the borrower to answer a security question first.",
    remedy: "Notify the borrower, or refresh the question",
  },
  BUREAU_KBA_SKIPPED: {
    label: "Security question skipped",
    detail: "The borrower did not complete the bureau's security question.",
    remedy: "Refresh the question and ask again",
  },
  BUREAU_PROVIDER_REJECTED_REQUEST: {
    label: "Bureau rejected our request",
    detail: "The bureau turned the request down because a required detail was missing or invalid.",
    remedy: "Check the borrower's details, then re-run",
  },
  BUREAU_REPORT_DISCARDED: {
    label: "Report was discarded",
    detail: "A real report came back and an earlier defect threw it away. The stored response still shows it existed.",
    remedy: "Re-run the credit check to recover it",
  },
  BUREAU_MASKED_MOBILE_FOLLOW_UP: {
    label: "File held under another number",
    detail: "The bureau has records for this identity under mobile numbers we do not hold.",
    remedy: "Manual bureau follow-up - re-running cannot resolve this",
  },
  BUREAU_PROVIDER_NO_BALANCE: {
    label: "Bureau account out of balance",
    detail: "The credit check could not be paid for at the time it ran.",
    remedy: "Top up the provider account, then re-run",
  },
  BUREAU_PROVIDER_PLAN_LIMIT: {
    label: "Report too large for our plan",
    detail: "The borrower's file is bigger than our current bureau plan returns.",
    remedy: "Vendor plan change required",
  },
  BUREAU_PROVIDER_UNAVAILABLE: {
    label: "Bureau unavailable",
    detail: "Every bureau we tried failed to respond. This is usually temporary.",
    remedy: "Re-run the credit check",
  },
  PAN_UNVERIFIED: {
    label: "PAN check unavailable",
    detail: "The PAN could not be verified - every provider we tried failed.",
    remedy: "Re-run the PAN check",
  },
  PAN_INVALID: {
    label: "PAN not recognised",
    detail: "Every provider independently reported this PAN as not issued.",
    remedy: "Data-quality or fraud review - not a system fault",
  },
  BUREAU_CONSENT_PENDING: {
    label: "Consent not given",
    detail: "The borrower has not completed the consent step, so no credit check has run.",
    remedy: "Borrower must finish onboarding",
  },
  BUREAU_NOT_RUN: {
    label: "Credit check not run",
    detail: "No credit check has been attempted for this application.",
    remedy: "Re-run the credit check",
  },
  BUREAU_NO_SCORE: {
    label: "Report, no score",
    detail: "The bureau returned a full report but no usable score. The accounts and history are readable.",
    remedy: "Decide from the account history",
  },
  BUREAU_NO_RECORD: {
    label: "No credit file",
    detail: "The bureau has no credit history for this identity. This is a real answer, not a failure.",
    remedy: "Decide on income and employment",
  },
  AWAITING_ASSIGNMENT: {
    label: "Awaiting assignment",
    detail: "Nothing is wrong - this file has not been assigned to a credit reviewer yet.",
    remedy: "Assign a credit executive",
  },
  NONE: {
    label: "",
    detail: "",
    remedy: "",
  },
};

/** `NONE` is the "nothing outstanding" answer — callers render an empty Failure cell for it. */
export function isNoFailure(reason: CaseFailureReason | null | undefined): boolean {
  return reason == null || reason === "NONE";
}

/** Failure-column cell text. `""` for `NONE`/unknown — render an empty cell, not a dash. */
export function caseFailureLabel(reason: CaseFailureReason | null | undefined): string {
  if (reason == null) return "";
  return CASE_FAILURE_INFO[reason]?.label ?? "";
}

/** One-sentence explanation for the failure detail dialog. */
export function caseFailureDetail(reason: CaseFailureReason | null | undefined): string {
  if (reason == null) return "";
  return CASE_FAILURE_INFO[reason]?.detail ?? "";
}

/** What staff should do about it, for the failure detail dialog. */
export function caseFailureRemedy(reason: CaseFailureReason | null | undefined): string {
  if (reason == null) return "";
  return CASE_FAILURE_INFO[reason]?.remedy ?? "";
}

/**
 * Badge tone per severity. BLOCKED reads as a hard stop (error), ADMIN_FIXABLE and ACTIONABLE as
 * things staff can act on now (warning), OPS as an account/vendor problem outside this application
 * (neutral — it is not this borrower's fault), and INFO as exactly that (default) — the same tone
 * `bureauStateLabel`'s NO_RECORD/NOT_FETCHED callers already use for "nothing to do here" copy.
 */
export const SEVERITY_BADGE_VARIANT: Record<CaseFailureSeverity, BadgeVariant> = {
  BLOCKED: "error",
  ADMIN_FIXABLE: "warning",
  ACTIONABLE: "warning",
  OPS: "neutral",
  INFO: "default",
};
