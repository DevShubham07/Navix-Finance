"use client";

import type { LeadOutcome } from "@/lib/api/applications";
import { StatusBadge } from "@/components/ui";

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
 *
 * <p>The tone comes from the console-wide `StatusBadge` map (`kind="lead"`); only the labels live here.
 */
export const OUTCOME_LABEL: Record<LeadOutcome, string> = {
  NEW: "Not yet worked",
  OUTREACHED: "Outreached",
  REJECTED: "Rejected",
  CONFIRMED: "Confirmed",
};

export function OutcomeChip({ outcome }: { outcome: LeadOutcome | null | undefined }) {
  if (!outcome) return <span className="text-muted">—</span>;
  return (
    <StatusBadge kind="lead" value={outcome}>
      {/* StatusBadge forwards no `title`, so the CONFIRMED provenance note rides on the label. */}
      <span title={outcome === "CONFIRMED" ? "Derived from this lead's application — not set by staff" : undefined}>
        {OUTCOME_LABEL[outcome]}
      </span>
    </StatusBadge>
  );
}
