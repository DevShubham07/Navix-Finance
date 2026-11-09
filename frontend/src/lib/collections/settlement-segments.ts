/**
 * The settlements register's status chips and its pre-flight separation-of-duties check.
 *
 * The register lists every settlement the backend returns; the chips only narrow what is shown.
 * "All" keeps PROPOSED rows on top because those are the only ones anyone can still act on.
 */

import type { SettlementStatusName } from "@/lib/api/applications";

export type SettlementSegment = "ALL" | SettlementStatusName;

export const SETTLEMENT_SEGMENTS: readonly SettlementSegment[] = ["ALL", "PROPOSED", "APPROVED", "REJECTED"];

export const SETTLEMENT_SEGMENT_LABEL: Record<SettlementSegment, string> = {
  ALL: "All",
  PROPOSED: "Proposed",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

type HasStatus = { status: string };

type Amounts = { outstandingPaise: number | null; settlementAmountPaise: number | null; grossDuePaise?: number | null };

/** Rows per chip. "ALL" counts every row, including any status this client does not know yet. */
export function settlementSegmentCounts(rows: ReadonlyArray<HasStatus>): Record<SettlementSegment, number> {
  const counts: Record<SettlementSegment, number> = { ALL: rows.length, PROPOSED: 0, APPROVED: 0, REJECTED: 0 };
  for (const r of rows) {
    if (r.status === "PROPOSED" || r.status === "APPROVED" || r.status === "REJECTED") counts[r.status] += 1;
  }
  return counts;
}

/**
 * The rows a chip shows, in server order — except "ALL", which moves PROPOSED rows to the top
 * (a stable partition: each group keeps the order the server sent it in).
 */
export function settlementsInSegment<T extends HasStatus>(rows: readonly T[], segment: SettlementSegment): T[] {
  if (segment !== "ALL") return rows.filter((r) => r.status === segment);
  const proposed: T[] = [];
  const rest: T[] = [];
  for (const r of rows) (r.status === "PROPOSED" ? proposed : rest).push(r);
  return [...proposed, ...rest];
}

/**
 * Whether the signed-in staffer proposed this settlement — in which case the server will refuse
 * their approve or reject with SOD_VIOLATION. Only a hint for the UI: the server check stays
 * authoritative, so an unknown proposer or an unknown session never counts as a match.
 */
export function isOwnSettlementProposal(
  settlement: { proposedBy: number | null },
  staffId: string | number | null | undefined,
): boolean {
  if (settlement.proposedBy == null || staffId == null) return false;
  const me = String(staffId).trim();
  return me !== "" && String(settlement.proposedBy) === me;
}

/**
 * What the settlement writes off: everything the borrower owed (principal + interest + penalty)
 * minus the agreed full-and-final amount. Falls back to today's outstanding when the itemised dues
 * are missing. Never negative.
 */
export function settlementWaiverPaise(s: Amounts): number | null {
  if (s.settlementAmountPaise == null) return null;
  const owed = s.grossDuePaise ?? s.outstandingPaise;
  if (owed == null) return null;
  return Math.max(0, owed - s.settlementAmountPaise);
}

/** The agreed amount as a whole-number share of everything owed, or null when that is unknown/zero. */
export function settlementRecoveryPct(s: Amounts): number | null {
  const owed = s.grossDuePaise ?? s.outstandingPaise;
  if (!owed || s.settlementAmountPaise == null) return null;
  return Math.round((s.settlementAmountPaise / owed) * 100);
}

export type SettlementTotalsRow = {
  count: number;
  grossDuePaise: number;
  penaltyPaise: number;
  paidPaise: number;
  settlementPaise: number;
  lossPaise: number;
};

type TotalsInput = Amounts & { status: string; penaltyPaise?: number | null; paidPaise?: number | null };

/**
 * The summary table above the register: per status and overall, the total owed, penalty, net paid,
 * agreed settlement and loss (write-off). A rejected settlement writes nothing off, so its loss
 * is zero and the overall loss is Pending (potential) + Approved (actual).
 */
export function settlementTotals(rows: ReadonlyArray<TotalsInput>): Record<SettlementSegment, SettlementTotalsRow> {
  const zero = (): SettlementTotalsRow => ({ count: 0, grossDuePaise: 0, penaltyPaise: 0, paidPaise: 0, settlementPaise: 0, lossPaise: 0 });
  const out: Record<SettlementSegment, SettlementTotalsRow> = { ALL: zero(), PROPOSED: zero(), APPROVED: zero(), REJECTED: zero() };
  for (const r of rows) {
    const buckets: SettlementSegment[] = ["ALL"];
    if (r.status === "PROPOSED" || r.status === "APPROVED" || r.status === "REJECTED") buckets.push(r.status);
    const loss = r.status === "REJECTED" ? 0 : settlementWaiverPaise(r) ?? 0;
    for (const b of buckets) {
      const t = out[b];
      t.count += 1;
      t.grossDuePaise += r.grossDuePaise ?? r.outstandingPaise ?? 0;
      t.penaltyPaise += r.penaltyPaise ?? 0;
      t.paidPaise += r.paidPaise ?? 0;
      t.settlementPaise += r.settlementAmountPaise ?? 0;
      t.lossPaise += loss;
    }
  }
  return out;
}
