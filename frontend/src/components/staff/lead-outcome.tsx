"use client";

import type { LeadOutcome } from "@/lib/api/applications";

/**
 * How a lead's outreach outcome renders, everywhere it renders.
 *
 * <p>Shared across the telecaller queue, the admin lead register, the admin DSA console and the DSA
 * portal so the four screens cannot drift on what a value is called — the same reason
 * `providerLine` is shared between the two verification surfaces.
 *
 * <p>`CONFIRMED` is styled apart from the others on purpose: it is the one value a human never sets.
 * It is derived server-side from the attributed application, so it reads as a fact about the lead
 * rather than a judgement someone recorded.
 */
const OUTCOME_PILL: Record<LeadOutcome, string> = {
  NEW: "bg-grey-100 text-muted",
  OUTREACHED: "bg-navy-tint text-navy",
  REJECTED: "bg-error-100 text-error-700",
  CONFIRMED: "bg-success-100 text-success-700",
};

export const OUTCOME_LABEL: Record<LeadOutcome, string> = {
  NEW: "Not yet worked",
  OUTREACHED: "Outreached",
  REJECTED: "Rejected",
  CONFIRMED: "Confirmed",
};

export function OutcomeChip({ outcome }: { outcome: LeadOutcome | null | undefined }) {
  if (!outcome) return <span className="text-muted">—</span>;
  return (
    <span
      className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold ${OUTCOME_PILL[outcome]}`}
      title={outcome === "CONFIRMED" ? "Derived from this lead's application — not set by staff" : undefined}
    >
      {OUTCOME_LABEL[outcome]}
    </span>
  );
}
