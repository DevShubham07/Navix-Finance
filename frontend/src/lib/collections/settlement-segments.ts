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
