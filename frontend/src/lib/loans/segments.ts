/**
 * Client-side loan segments for the staff Loans register. Unlike the customer CRM (which straddles
 * application status and loan status because a customer may not have a live loan yet), every row
 * here already IS a disbursed loan — so segmentation reads the loan's own effective status string
 * directly, no application-status fallback needed.
 */

import type { BadgeVariant } from "@/components/ui/badge";

/** Minimal shape needed for segmenting (avoids pulling the full register row type into this module). */
export type SegmentableLoan = {
  status?: string | null;
};

export type LoanSegment = "active" | "overdue" | "closed" | "all";

export const SEGMENT_LABEL: Record<LoanSegment, string> = {
  all: "All",
  active: "Active",
  overdue: "Overdue",
  closed: "Closed",
};

/** Ordered chips / nav children (excludes `all` which is the default list view). */
export const SEGMENTS: LoanSegment[] = ["active", "overdue", "closed"];

/** Badge tone per segment — variant names from the shared `Badge` component, not raw colours. */
export const SEGMENT_TONE: Record<LoanSegment, BadgeVariant> = {
  active: "success",
  overdue: "error",
  closed: "neutral",
  all: "neutral",
};

const OVERDUE_STATUS = new Set(["OVERDUE", "IN_COLLECTIONS"]);
const CLOSED_STATUS = new Set(["REPAID", "CLOSED"]);

/**
 * Lifecycle segment for one loan row. Anything that isn't recognizably overdue or closed defaults
 * to "active" — a disbursed loan not yet closed and not (yet) flagged overdue by the backend is,
 * by construction, still live and should surface in the working queue rather than silently vanish
 * from every chip.
 */
export function segmentOf(row: Pick<SegmentableLoan, "status">): Exclude<LoanSegment, "all"> {
  const status = row.status ?? null;

  if (status && OVERDUE_STATUS.has(status)) return "overdue";
  if (status && CLOSED_STATUS.has(status)) return "closed";

  return "active";
}

export function inSegment(row: SegmentableLoan, seg: LoanSegment): boolean {
  if (seg === "all") return true;
  return segmentOf(row) === seg;
}

export type SegmentCounts = Record<LoanSegment, number>;

/** One pass over rows → counts per segment (and `all === rows.length`). */
export function segmentCounts(rows: SegmentableLoan[]): SegmentCounts {
  const counts: SegmentCounts = {
    all: rows.length,
    active: 0,
    overdue: 0,
    closed: 0,
  };
  for (const row of rows) {
    counts[segmentOf(row)]++;
  }
  return counts;
}
