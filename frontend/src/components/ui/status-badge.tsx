import * as React from "react";
import { Badge, type BadgeVariant } from "./badge";
import type {
  ApplicationStatus,
  CheckStatus,
  CollectionPaymentStatusName,
  LeadCallStatus,
  LeadOutcome,
  PaymentStatusName,
  SettlementStatusName,
} from "@/lib/api/applications";

/**
 * One status→tone map for the whole console.
 *
 * Today ~11 hand-written status→class maps sit inside a population of 31 files carrying ad-hoc
 * colour logic, so the same status wears different colours on different pages and a new status
 * silently renders unstyled. `ui/Badge` already defines the vocabulary
 * (`success`/`warning`/`error`/`info`/`neutral`/`primary`/`default`) and the sidebar already uses
 * it; this maps every backend enum onto it once.
 *
 * The tones encode *what the operator should do*, not the enum's name:
 *  - `success` — terminal good (passed, verified, closed, repaid)
 *  - `warning` — waiting on someone (pending, proposed, in review)
 *  - `error`   — terminal bad or needs intervention (rejected, failed, overdue, defaulted)
 *  - `info`    — live and healthy (active, disbursed, sanctioned)
 *  - `neutral` — not started / not applicable / advisory
 *
 * Deliberate call on `EMPLOYMENT`-style advisory checks: a failing employment check never gates a
 * transition (CLAUDE.md §14 — "no verification hard-blocks a borrower"), so callers rendering an
 * advisory check must pass `kind="advisory"` rather than `"verification"`, which keeps PASS/FAIL
 * out of the gating colours that imply a decision was blocked.
 *
 * `statusLabel`-style title-casing is applied to the label so a caller never has to; pass
 * `children` to override the text while keeping the tone.
 */
export type StatusKind =
  | "application"
  | "payment"
  | "collectionPayment"
  | "settlement"
  | "verification"
  | "advisory"
  | "lead"
  | "leadCall";

const APPLICATION: Record<ApplicationStatus, BadgeVariant> = {
  DRAFT: "neutral",
  KYC_PENDING: "warning",
  KYC_APPROVED: "info",
  KYC_REJECTED: "error",
  PRE_APPROVED: "info",
  REVIEW_PENDING: "warning",
  CREDIT_EXEC_PENDING: "warning",
  CREDIT_EXEC_APPROVED: "info",
  CREDIT_HEAD_PENDING: "warning",
  CREDIT_HEAD_APPROVED: "info",
  SANCTIONED: "info",
  DISBURSEMENT_PENDING: "warning",
  ACCOUNTANT_PENDING: "warning",
  DISBURSEMENT_FAILED: "error",
  DISBURSED: "info",
  ACTIVE: "info",
  OVERDUE: "error",
  DEFAULTED: "error",
  CLOSED: "success",
  WRITTEN_OFF: "error",
  REJECTED: "error",
  CANCELLED: "neutral",
};

const PAYMENT: Record<PaymentStatusName, BadgeVariant> = {
  PENDING_VERIFICATION: "warning",
  VERIFIED: "success",
  REJECTED: "error",
};

/**
 * Note the terminal state here is `VALIDATED`, not `VERIFIED` — a collections payment is validated
 * by the Accountant while a borrower repayment is *verified*. The two vocabularies are genuinely
 * different and the exhaustive `Record` is what keeps them from being conflated.
 */
const COLLECTION_PAYMENT: Record<CollectionPaymentStatusName, BadgeVariant> = {
  PENDING_HEAD: "warning",
  PENDING_ACCOUNTANT: "warning",
  VALIDATED: "success",
  REJECTED: "error",
};

const SETTLEMENT: Record<SettlementStatusName, BadgeVariant> = {
  PROPOSED: "warning",
  APPROVED: "success",
  REJECTED: "error",
};

const VERIFICATION: Record<CheckStatus, BadgeVariant> = {
  PASS: "success",
  FAIL: "error",
  REVIEW: "warning",
  PENDING: "neutral",
};

/**
 * Advisory checks (today: employment/EPFO) never gate a transition, so PASS/FAIL must not borrow
 * the gating colours — a red chip beside a file that is perfectly sanctionable reads as a blocker.
 */
const ADVISORY: Record<CheckStatus, BadgeVariant> = {
  PASS: "info",
  FAIL: "info",
  REVIEW: "neutral",
  PENDING: "neutral",
};

const LEAD: Record<LeadOutcome, BadgeVariant> = {
  NEW: "neutral",
  OUTREACHED: "info",
  REJECTED: "error",
  CONFIRMED: "success",
};

const LEAD_CALL: Record<LeadCallStatus, BadgeVariant> = {
  NOT_CALLED: "neutral",
  CALLED: "info",
  CALLBACK: "warning",
  NO_ANSWER: "warning",
  NOT_INTERESTED: "error",
  WRONG_NUMBER: "error",
  CONNECTED: "success",
};

const MAPS: Record<StatusKind, Record<string, BadgeVariant>> = {
  application: APPLICATION,
  payment: PAYMENT,
  collectionPayment: COLLECTION_PAYMENT,
  settlement: SETTLEMENT,
  verification: VERIFICATION,
  advisory: ADVISORY,
  lead: LEAD,
  leadCall: LEAD_CALL,
};

/** `KYC_PENDING` -> `Kyc Pending`. Mirrors `statusLabel`, for every enum rather than one. */
export function humaniseStatus(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/** The tone a status resolves to. Exported so tests can assert the map without rendering. */
export function statusVariant(kind: StatusKind, value: string | null | undefined): BadgeVariant {
  if (!value) return "neutral";
  // An unmapped value must not throw or render unstyled — a backend enum can gain a member before
  // the frontend knows about it, and a register must still paint.
  return MAPS[kind][value] ?? "neutral";
}

export interface StatusBadgeProps {
  kind: StatusKind;
  value: string | null | undefined;
  size?: "sm" | "md" | "lg";
  className?: string;
  children?: React.ReactNode;
}

export function StatusBadge({ kind, value, size = "sm", className, children }: StatusBadgeProps) {
  if (!value) return <span className="text-muted">—</span>;
  return (
    <Badge variant={statusVariant(kind, value)} size={size} className={className}>
      {children ?? humaniseStatus(value)}
    </Badge>
  );
}
