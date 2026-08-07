/**
 * Client-side customer segments for the staff CRM. Loan state outranks application status —
 * the application aggregate stays ACTIVE for the entire overdue window, so latestStatus alone
 * can never say "Overdue".
 */

/** Minimal shape needed for segmenting (avoids pulling the full API client into this module). */
export type SegmentableCustomer = {
  loanStatus?: string | null;
  latestStatus?: string | null;
  ownerStaffId?: number | null;
};

export type CustomerSegment =
  | "incomplete"
  | "pending"
  | "review"
  | "approved"
  | "active"
  | "overdue"
  | "hold"
  | "rejected"
  | "closed"
  | "unallocated"
  | "all";

export const SEGMENT_LABEL: Record<CustomerSegment, string> = {
  all: "All",
  incomplete: "Incomplete",
  pending: "Pending",
  review: "Review",
  approved: "Approved",
  active: "Active",
  overdue: "Overdue",
  hold: "Hold",
  rejected: "Rejected",
  closed: "Closed",
  unallocated: "Unallocated",
};

/** Ordered chips / nav children (excludes `all` which is the default list view). */
export const SEGMENTS: CustomerSegment[] = [
  "incomplete",
  "pending",
  "review",
  "approved",
  "active",
  "overdue",
  "hold",
  "rejected",
  "closed",
  "unallocated",
];

const OVERDUE_LOAN = new Set(["OVERDUE", "IN_COLLECTIONS"]);
const ACTIVE_LOAN = new Set(["ACTIVE", "DISBURSING"]);
const OVERDUE_APP = new Set(["OVERDUE", "DEFAULTED"]);
const ACTIVE_APP = new Set(["DISBURSED", "ACTIVE"]);
// DRAFT is deliberately NOT "pending": the DRAFT row is minted right after mobile-OTP, so it means
// "abandoned mid-onboarding, nobody can action it" — a chase/call target, not a staff queue item.
const PENDING_APP = new Set(["KYC_PENDING", "PRE_APPROVED"]);
const REVIEW_APP = new Set(["REVIEW_PENDING", "CREDIT_EXEC_PENDING", "CREDIT_HEAD_PENDING"]);
const APPROVED_APP = new Set([
  "KYC_APPROVED",
  "CREDIT_EXEC_APPROVED",
  "CREDIT_HEAD_APPROVED",
  // The credit team has decided; the borrower is walking the post-approval journey (V45). Without
  // this the row falls through segmentOf's default and shows as "pending" — decided but invisible.
  "SANCTIONED",
  "DISBURSEMENT_PENDING",
  "ACCOUNTANT_PENDING",
]);
const REJECTED_APP = new Set(["KYC_REJECTED", "REJECTED"]);
const CLOSED_APP = new Set(["CLOSED", "WRITTEN_OFF", "CANCELLED"]);

/** Lifecycle segment for one customer row (loan status outranks application status). */
export function segmentOf(
  c: Pick<SegmentableCustomer, "loanStatus" | "latestStatus">,
): Exclude<CustomerSegment, "unallocated" | "all"> {
  const loan = c.loanStatus ?? null;
  const app = c.latestStatus ?? null;

  if (loan && OVERDUE_LOAN.has(loan)) return "overdue";
  if (app && OVERDUE_APP.has(app)) return "overdue";

  if (loan && ACTIVE_LOAN.has(loan)) return "active";
  if (app && ACTIVE_APP.has(app)) return "active";

  if (app && REVIEW_APP.has(app)) return "review";
  if (app && APPROVED_APP.has(app)) return "approved";
  if (app === "DISBURSEMENT_FAILED") return "hold";
  if (app === "DRAFT") return "incomplete";
  if (app && REJECTED_APP.has(app)) return "rejected";
  if (app && CLOSED_APP.has(app)) return "closed";
  if (app && PENDING_APP.has(app)) return "pending";

  return "pending";
}

export function inSegment(c: SegmentableCustomer, seg: CustomerSegment): boolean {
  if (seg === "all") return true;
  if (seg === "unallocated") return c.ownerStaffId == null;
  return segmentOf(c) === seg;
}

export type SegmentCounts = Record<CustomerSegment, number>;

/** One pass over rows → counts per segment (and `all === rows.length`). */
export function segmentCounts(rows: SegmentableCustomer[]): SegmentCounts {
  const counts: SegmentCounts = {
    all: rows.length,
    incomplete: 0,
    pending: 0,
    review: 0,
    approved: 0,
    active: 0,
    overdue: 0,
    hold: 0,
    rejected: 0,
    closed: 0,
    unallocated: 0,
  };
  for (const c of rows) {
    counts[segmentOf(c)]++;
    if (c.ownerStaffId == null) counts.unallocated++;
  }
  return counts;
}
