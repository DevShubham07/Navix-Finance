/**
 * Pure view logic for `/staff/my-decisions` — kept out of the page so it can be unit-tested.
 *
 * The server decides whose history may be opened (`DecisionHistoryService`); nothing here gates
 * access. These helpers only filter, label and sort rows the server already returned.
 */

import type { DecisionView } from "@/lib/api/applications";

/** Verb per audited action, so the log reads as a sentence rather than an enum dump. */
export const DECISION_ACTION_LABEL: Record<string, string> = {
  ASSIGN: "Assigned to an executive",
  SANCTION: "Accepted lead (sanctioned)",
  REJECT_LEAD: "Rejected lead",
  MARK_PENDING: "Marked lead pending",
  KYC_APPROVE: "Cleared KYC",
  KYC_REJECT: "Rejected KYC",
  // Retired with the credit maker-checker (V45) — historical rows still carry them.
  EXEC_APPROVE: "Recommended (legacy)",
  EXEC_REJECT: "Rejected at credit review (legacy)",
  HEAD_APPROVE: "Approved as Credit Head (legacy)",
  HEAD_REJECT: "Rejected as Credit Head (legacy)",
  DISB_ACCEPT: "Released for disbursal",
  DISB_REJECT: "Rejected at disbursement",
  VALIDATE_SUCCESS: "Confirmed transfer",
  VALIDATE_FAIL: "Marked transfer failed",
  RETRY: "Retried disbursement",
  CANCEL: "Cancelled",
  REASSIGN: "Reassigned to another executive",
};

export function decisionActionLabel(action: string): string {
  return DECISION_ACTION_LABEL[action] ?? action;
}

/**
 * A decision with the two derived fields its sortable columns need: `atMs` so "When" sorts by the
 * instant rather than by the timestamp's text, and `decisionLabel` so "Decision" sorts by the words
 * on screen rather than by the enum code behind them.
 */
export type DecisionRow = DecisionView & { atMs: number | null; decisionLabel: string };

export function toDecisionRow(row: DecisionView): DecisionRow {
  const ms = Date.parse(row.at);
  return { ...row, atMs: Number.isNaN(ms) ? null : ms, decisionLabel: decisionActionLabel(row.action) };
}

/**
 * Client-side search over application id, customer id, customer name and PAN. Case-insensitive
 * substring match; a leading `#` is ignored so "#318" finds what the table prints as "#318". A blank
 * term matches everything.
 */
export function decisionMatchesSearch(row: DecisionView, term: string): boolean {
  const needle = term.trim().replace(/^#/, "").toLowerCase();
  if (!needle) return true;
  const haystack = [
    String(row.applicationId),
    row.customerId != null ? String(row.customerId) : "",
    row.customerName ?? "",
    row.pan ?? "",
  ];
  return haystack.some((v) => v.toLowerCase().includes(needle));
}

/**
 * The query string with `staffId`, `from` and `to` written into it — blank values deleted, every
 * other parameter kept. Returned normalised by `URLSearchParams`, so a caller can compare it with
 * the current (also normalised) query string and skip a no-op `router.replace`.
 */
export function withDecisionHistoryParams(
  current: string,
  next: { staffId?: string; from?: string; to?: string },
): string {
  const p = new URLSearchParams(current);
  for (const key of ["staffId", "from", "to"] as const) {
    const value = next[key];
    if (value) p.set(key, value);
    else p.delete(key);
  }
  return p.toString();
}

/**
 * A React Query `placeholderData` function that carries the previous result across a key change
 * ONLY when the key's identity segment (whose data this is) is unchanged. A period change keeps
 * the rows on screen while the next window loads; a change of person clears to the skeleton, so
 * one staffer's rows are never shown under another's name.
 */
export function placeholderForSameIdentity<T>(identityIndex: number, identity: unknown) {
  return (previous: T | undefined, previousQuery: { queryKey: readonly unknown[] } | undefined): T | undefined =>
    previousQuery !== undefined && previousQuery.queryKey[identityIndex] === identity ? previous : undefined;
}
