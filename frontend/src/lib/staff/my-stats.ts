/**
 * Pure stat engine for a staffer's personal dashboard (revamp: "my numbers" replacing the generic
 * role dashboard). No React, no fetch — every function is a plain transform over data the page
 * already fetched via the typed API client. Rule for every rate/average below: an unmeasurable
 * metric (zero denominator, no data) is `null`, never `0` — a `0` reads as a real, bad number.
 */

import type {
  CaseView,
  CollectionPaymentView,
  CustomerSummary,
  DecisionView,
  DpdBucket,
  SettlementView,
  StaffActivityPoint,
  StaffPerformanceSummary,
} from "@/lib/api/applications";
import { daysBetween } from "@/lib/calc/loan-math";
import { segmentCounts, segmentOf, type SegmentCounts } from "@/lib/customers/segments";

// ---------------------------------------------------------------------------
// decisionStats — throughput & SLA, off /staff/performance's StaffPerformanceSummary
// ---------------------------------------------------------------------------

export interface DecisionStats {
  decided: number;
  approved: number;
  rejected: number;
  /** null when approved+rejected === 0 */
  approvalRate: number | null;
  /** Live snapshot of files sitting with them right now — NOT windowed by the period picker. */
  pendingNow: number;
  /** Mean of each row's avgTurnaroundMinutes, weighted by totalActions; rows with a null value are
   *  skipped entirely (not treated as 0). null when no row has a value. */
  avgTurnaroundMinutes: number | null;
  valuePaise: number;
  activeDays: number;
  /** decided / activeDays; null when activeDays is 0. */
  actionsPerActiveDay: number | null;
  busiestDay: StaffActivityPoint | null;
  firstActionAt: string | null;
  lastActionAt: string | null;
  callsMade: number;
  /** false when the window starts before summary.callTrackingSince — renderer must hide the tile,
   *  a 0 there means "not tracked", not "made none". */
  callsTracked: boolean;
  daily: StaffActivityPoint[];
}

export function decisionStats(
  summary: StaffPerformanceSummary,
  windowStart: string | null | undefined,
): DecisionStats {
  const rows = summary.rows;

  let decided = 0;
  let approved = 0;
  let rejected = 0;
  let pendingNow = 0;
  let valuePaise = 0;
  let activeDays = 0;
  let callsMade = 0;
  let turnoverWeightedSum = 0;
  let turnoverWeight = 0;
  let firstActionAt: string | null = null;
  let lastActionAt: string | null = null;

  for (const r of rows) {
    decided += r.totalActions;
    approved += r.accepted;
    rejected += r.rejected;
    pendingNow += r.pendingNow;
    valuePaise += r.moneyPaise;
    activeDays += r.activeDays;
    callsMade += r.callsMade;
    if (r.avgTurnaroundMinutes != null) {
      turnoverWeightedSum += r.avgTurnaroundMinutes * r.totalActions;
      turnoverWeight += r.totalActions;
    }
    if (r.firstActionAt != null && (firstActionAt == null || r.firstActionAt < firstActionAt)) {
      firstActionAt = r.firstActionAt;
    }
    if (r.lastActionAt != null && (lastActionAt == null || r.lastActionAt > lastActionAt)) {
      lastActionAt = r.lastActionAt;
    }
  }

  let busiestDay: StaffActivityPoint | null = null;
  for (const d of summary.daily) {
    if (busiestDay == null || d.actions > busiestDay.actions) busiestDay = d;
  }

  return {
    decided,
    approved,
    rejected,
    approvalRate: approved + rejected > 0 ? approved / (approved + rejected) : null,
    pendingNow,
    avgTurnaroundMinutes: turnoverWeight > 0 ? turnoverWeightedSum / turnoverWeight : null,
    valuePaise,
    activeDays,
    actionsPerActiveDay: activeDays > 0 ? decided / activeDays : null,
    busiestDay,
    firstActionAt,
    lastActionAt,
    callsMade,
    callsTracked: windowStart != null && windowStart >= summary.callTrackingSince,
    daily: summary.daily,
  };
}

// ---------------------------------------------------------------------------
// outcomeStats — decision quality: what happened to the borrowers I approved
// ---------------------------------------------------------------------------

/**
 * MIRRORS DecisionHistoryService.ACCEPT_ACTIONS / REJECT_ACTIONS (navix-loan) — that Java file is
 * the source of truth, and these must not drift from it: the same dashboard shows the backend's
 * own approved/rejected counts beside the outcome counts derived here, so a different vocabulary
 * puts two contradictory numbers on one screen.
 *
 * Deliberately NOT the fuller ACTION_LABEL map in app/staff/my-decisions/page.tsx: that map still
 * lists EXEC_APPROVE / HEAD_APPROVE / DISB_ACCEPT / VALIDATE_FAIL, which nothing has emitted since
 * V45/V48, and omits ADMIN_FORCE_DISBURSE and CANCEL, which are real decisions.
 *
 * AUTO_REJECT_* is excluded for the backend's reason: it is written by the system's own auto-reject
 * rules, so crediting it to whoever tripped the rule would inflate their rejections with work they
 * never did.
 */
const ACCEPT_ACTIONS = new Set([
  "KYC_APPROVE",
  "SANCTION",
  "VALIDATE_SUCCESS",
  "ADMIN_FORCE_DISBURSE",
]);
const REJECT_ACTIONS = new Set([
  "KYC_REJECT",
  "REJECT_LEAD",
  "DISB_REJECT",
  "CANCEL",
]);

export interface OutcomeStats {
  approvedCount: number;
  nowOverdue: number;
  repaidClean: number;
  stillLive: number;
  /** null when approvedCount is 0 */
  overdueRate: number | null;
  /** overdueOutstandingPaise / liveOutstandingPaise; null when the denominator is 0 */
  parPct: number | null;
  overdueOutstandingPaise: number;
  liveOutstandingPaise: number;
  /** null when no approved customer has amountIsRequested === true */
  avgSanctionedPaise: number | null;
  avgScoreApproved: number | null;
  avgScoreRejected: number | null;
  starMix: Record<1 | 2 | 3 | 4 | 5, number>;
}

function decidedCustomers(
  decisions: DecisionView[],
  actions: Set<string>,
  book: CustomerSummary[],
): CustomerSummary[] {
  const byId = new Map(book.map((c) => [c.customerId, c]));
  const ids = new Set<number>();
  for (const d of decisions) {
    if (d.customerId != null && actions.has(d.action)) ids.add(d.customerId);
  }
  const out: CustomerSummary[] = [];
  for (const id of ids) {
    const c = byId.get(id);
    if (c) out.push(c); // silently skip a decision whose customer isn't in the book
  }
  return out;
}

function mean(values: number[]): number | null {
  if (values.length === 0) return null;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

export function outcomeStats(decisions: DecisionView[], book: CustomerSummary[]): OutcomeStats {
  const approvedCustomers = decidedCustomers(decisions, ACCEPT_ACTIONS, book);
  const rejectedCustomers = decidedCustomers(decisions, REJECT_ACTIONS, book);

  const overdue = approvedCustomers.filter((c) => segmentOf(c) === "overdue");
  const closed = approvedCustomers.filter((c) => segmentOf(c) === "closed");
  const active = approvedCustomers.filter((c) => segmentOf(c) === "active");

  const overdueOutstandingPaise = overdue.reduce((s, c) => s + c.totalOutstandingPaise, 0);
  const liveOutstandingPaise = active.reduce((s, c) => s + c.totalOutstandingPaise, 0);

  const sanctioned = approvedCustomers
    .filter((c) => c.amountIsRequested && c.amountPaise != null)
    .map((c) => c.amountPaise as number);

  const scoresApproved = approvedCustomers
    .filter((c) => c.creditScore != null)
    .map((c) => c.creditScore as number);
  const scoresRejected = rejectedCustomers
    .filter((c) => c.creditScore != null)
    .map((c) => c.creditScore as number);

  const starMix: Record<1 | 2 | 3 | 4 | 5, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 };
  for (const c of approvedCustomers) {
    if (c.starRating != null && c.starRating >= 1 && c.starRating <= 5) {
      starMix[c.starRating as 1 | 2 | 3 | 4 | 5]++;
    }
  }

  return {
    approvedCount: approvedCustomers.length,
    nowOverdue: overdue.length,
    repaidClean: closed.length,
    stillLive: active.length,
    overdueRate: approvedCustomers.length > 0 ? overdue.length / approvedCustomers.length : null,
    parPct: liveOutstandingPaise > 0 ? overdueOutstandingPaise / liveOutstandingPaise : null,
    overdueOutstandingPaise,
    liveOutstandingPaise,
    avgSanctionedPaise: mean(sanctioned),
    avgScoreApproved: mean(scoresApproved),
    avgScoreRejected: mean(scoresRejected),
    starMix,
  };
}

// ---------------------------------------------------------------------------
// bookStats — my borrowers, lifecycle + exposure + DPD
// ---------------------------------------------------------------------------

export interface BookStats {
  total: number;
  counts: SegmentCounts;
  outstandingPaise: number;
  atRiskPaise: number;
  dpd: { d1to30: number; d31to60: number; d60plus: number };
  dueNext7Days: number;
  avgTicketPaise: number | null;
  largestExposurePaise: number;
  /** null when total outstanding across the book is 0 */
  concentrationPct: number | null;
  repeatBorrowers: number;
  avgCreditScore: number | null;
  thinFile: number;
  toChase: number;
}

export function bookStats(book: CustomerSummary[], today: Date): BookStats {
  const outstandingPaise = book.reduce((s, c) => s + c.totalOutstandingPaise, 0);
  const atRiskPaise = book
    .filter((c) => segmentOf(c) === "overdue")
    .reduce((s, c) => s + c.totalOutstandingPaise, 0);

  let d1to30 = 0;
  let d31to60 = 0;
  let d60plus = 0;
  let dueNext7Days = 0;
  for (const c of book) {
    if (!c.loanDueDate) continue;
    const due = new Date(`${c.loanDueDate}T00:00:00`);
    const daysLate = daysBetween(due, today);
    if (daysLate >= 1 && daysLate <= 30) d1to30++;
    else if (daysLate >= 31 && daysLate <= 60) d31to60++;
    else if (daysLate > 60) d60plus++;
    if (daysLate >= -7 && daysLate < 0) dueNext7Days++;
  }

  const tickets = book
    .filter((c) => c.amountIsRequested && c.amountPaise != null)
    .map((c) => c.amountPaise as number);
  const scores = book.filter((c) => c.creditScore != null).map((c) => c.creditScore as number);

  const largestExposurePaise = book.reduce((max, c) => Math.max(max, c.totalOutstandingPaise), 0);

  return {
    total: book.length,
    counts: segmentCounts(book),
    outstandingPaise,
    atRiskPaise,
    dpd: { d1to30, d31to60, d60plus },
    dueNext7Days,
    avgTicketPaise: mean(tickets),
    largestExposurePaise,
    concentrationPct: outstandingPaise > 0 ? largestExposurePaise / outstandingPaise : null,
    repeatBorrowers: book.filter((c) => c.loanCount > 1).length,
    avgCreditScore: mean(scores),
    thinFile: book.filter((c) => c.bureauState === "NO_RECORD").length,
    toChase: book.filter((c) => c.latestStatus === "DRAFT").length,
  };
}

// ---------------------------------------------------------------------------
// collectionsStats — pure filtering over the collections payloads
// ---------------------------------------------------------------------------

export interface CollectionsStats {
  myCases: CaseView[];
  byBucket: Record<DpdBucket, CaseView[]>;
  myCasesOutstandingPaise: number;
  recoveredPaise: number;
  awaitingValidation: number;
  settlementsProposed: { proposed: number; approved: number; rejected: number };
  settlementsApproved: number;
  concededPaise: number;
  /** null when myCasesOutstandingPaise is 0 */
  recoveryRate: number | null;
}

const DPD_BUCKETS: DpdBucket[] = ["UPCOMING", "T0_T7", "T8_T30", "T30_T60", "T60_T90", "T90_PLUS"];

export function collectionsStats(
  cases: CaseView[],
  settlements: SettlementView[],
  payments: CollectionPaymentView[],
  staffId: number,
): CollectionsStats {
  const myCases = cases.filter((c) => c.assignedOfficerId === staffId);

  const byBucket = Object.fromEntries(DPD_BUCKETS.map((b) => [b, [] as CaseView[]])) as Record<
    DpdBucket,
    CaseView[]
  >;
  for (const c of myCases) byBucket[c.bucket].push(c);

  const myCasesOutstandingPaise = myCases.reduce((s, c) => s + (c.outstandingPaise ?? 0), 0);

  const myPayments = payments.filter((p) => p.raisedBy === staffId);
  const recoveredPaise = myPayments
    .filter((p) => p.status === "VALIDATED")
    .reduce((s, p) => s + (p.amountPaise ?? 0), 0);
  const awaitingValidation = myPayments.filter((p) => p.status === "PENDING_ACCOUNTANT").length;

  const mySettlementsProposed = settlements.filter((s) => s.proposedBy === staffId);
  const settlementsProposed = {
    proposed: mySettlementsProposed.filter((s) => s.status === "PROPOSED").length,
    approved: mySettlementsProposed.filter((s) => s.status === "APPROVED").length,
    rejected: mySettlementsProposed.filter((s) => s.status === "REJECTED").length,
  };

  const mySettlementsApproved = settlements.filter((s) => s.approvedBy === staffId);
  const concededPaise = mySettlementsApproved.reduce(
    (s, x) => s + (x.settlementAmountPaise ?? 0),
    0,
  );

  return {
    myCases,
    byBucket,
    myCasesOutstandingPaise,
    recoveredPaise,
    awaitingValidation,
    settlementsProposed,
    settlementsApproved: mySettlementsApproved.length,
    concededPaise,
    recoveryRate: myCasesOutstandingPaise > 0 ? recoveredPaise / myCasesOutstandingPaise : null,
  };
}
