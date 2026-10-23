/**
 * What "performance" means for each kind of staffer on `/staff/performance`.
 *
 * One register with credit columns for everybody made a Collection Executive look idle (0 approved,
 * 0 rejected) however many calls they made. Each entity here names its own parameters instead —
 * a Credit Head is measured on assignments, a Collection Executive on calls, promises and money
 * recovered, an Accountant on what they verified — and the page renders the tiles, the table and the
 * per-person pop-up from this config. Pure data + functions so it can be unit-tested.
 *
 * Nothing here scopes who may be seen: the roster is the server's (`DecisionHistoryService.rosterFor`).
 */

import { paiseToINR, type CollectionStaffStats, type StaffPerformanceRow, type StaffRoleName } from "@/lib/api/applications";

export type MetricFormat = "count" | "money" | "minutes" | "pct";

export interface Metric {
  /** Also the sort key in the URL and the property on a {@link PerformanceViewRow}. */
  key: string;
  label: string;
  fmt: MetricFormat;
  /** What the figure counts, in a sentence — shown as the ⓘ tooltip and in the pop-up. */
  info: string;
  /** null = not measurable for this row (renders "—"), never a fabricated 0. */
  value: (r: StaffPerformanceRow) => number | null;
  /** For a `pct` metric: the parts, so a tile's total is Σnum/Σden rather than a mean of means. */
  ratio?: { num: (r: StaffPerformanceRow) => number; den: (r: StaffPerformanceRow) => number };
  /** A snapshot of right now, not affected by the period picker. */
  live?: boolean;
  tone?: "success" | "error" | "gold";
  /** Calls are only attributable since V59 — the page adds the partial-window note to these. */
  calls?: boolean;
}

export type EntityKey =
  | "CREDIT_EXECUTIVE"
  | "CREDIT_HEAD"
  | "COLLECTION_EXECUTIVE"
  | "COLLECTION_HEAD"
  | "DISBURSEMENT_HEAD"
  | "ACCOUNTANT"
  | "TELECALLER"
  | "ADMIN"
  | "ALL";

export interface PerformanceEntity {
  key: EntityKey;
  label: string;
  /** One line under the switcher saying what this view measures. */
  blurb: string;
  /** Roster roles shown under this entity; empty = everyone (the "All staff" overview). */
  roles: StaffRoleName[];
  /** Every metric, in table-column order. */
  metrics: Metric[];
  /** The headline tiles, by metric key (≤ 5 + any live tile). */
  tiles: string[];
  /** Default table sort — the entity's headline metric, largest first. */
  defaultSort: string;
}

export const NO_COLLECTIONS: CollectionStaffStats = {
  interactions: 0,
  connected: 0,
  promisesToPay: 0,
  paidOutcomes: 0,
  noAnswer: 0,
  disputed: 0,
  casesNow: 0,
  paymentsRaised: 0,
  paymentsRaisedPaise: 0,
  paymentsRecovered: 0,
  paymentsRecoveredPaise: 0,
  paymentsPending: 0,
  paymentsRejected: 0,
  settlementsProposed: 0,
  settlementsProposedPaise: 0,
  settlementsApproved: 0,
  settlementsApprovedPaise: 0,
  settlementsRejected: 0,
  paymentsValidated: 0,
  paymentsValidatedPaise: 0,
  paymentsValidationRejected: 0,
};

/** The row's collections block — tolerant of a backend that predates it, during a rolling deploy. */
export function col(r: StaffPerformanceRow): CollectionStaffStats {
  return r.collections ?? NO_COLLECTIONS;
}

/** How many times they took `action` in the window (0 when none). */
export function act(r: StaffPerformanceRow, ...actions: string[]): number {
  const counts = r.actionCounts ?? {};
  return actions.reduce((n, a) => n + (counts[a] ?? 0), 0);
}

/** Rate as a 0–100 number, or null when there is nothing to divide by — never a fake 0%. */
export function pct(num: number, den: number): number | null {
  return den > 0 ? Math.round((num / den) * 1000) / 10 : null;
}

/** Calls logged as a telecaller — the total less the collections interactions folded into it. */
function leadCalls(r: StaffPerformanceRow): number {
  return Math.max(0, r.callsMade - col(r).interactions);
}

// ---- shared metrics ----------------------------------------------------------------------------

const ACTIVE_DAYS: Metric = {
  key: "activeDays",
  label: "Active days",
  fmt: "count",
  info: "Distinct days (IST) on which they did anything — a decision, a call, a payment, a settlement.",
  value: (r) => r.activeDays,
};

const TOTAL_ACTIONS: Metric = {
  key: "totalActions",
  label: "File actions",
  fmt: "count",
  info: "Every action they took on an application in the period, routing steps like assignment included. System auto-rejects are not counted.",
  value: (r) => r.totalActions,
};

const TURNAROUND: Metric = {
  key: "avgTurnaroundMinutes",
  label: "Avg turnaround",
  fmt: "minutes",
  info: "Mean time from a file being assigned to them until they decided it. Blank when no decision in the period had a matching assignment to time from — not a zero wait.",
  value: (r) => r.avgTurnaroundMinutes,
};

const SANCTIONED: Metric = {
  key: "sanctioned",
  label: "Sanctioned",
  fmt: "count",
  tone: "success",
  info: "Files they sanctioned (amount + repayment date fixed) in the period.",
  value: (r) => act(r, "SANCTION"),
};

const CREDIT_REJECTED: Metric = {
  key: "creditRejected",
  label: "Rejected",
  fmt: "count",
  tone: "error",
  info: "Leads they rejected (30-day cooling-off) plus KYC rejections, in the period.",
  value: (r) => act(r, "REJECT_LEAD", "KYC_REJECT"),
};

const SANCTIONED_VALUE: Metric = {
  key: "sanctionedPaise",
  label: "Value sanctioned",
  fmt: "money",
  info: "Total amount across the sanctions they issued in the period.",
  value: (r) => r.sanctionedPaise ?? 0,
};

const ON_CREDIT_DESK: Metric = {
  key: "pendingNow",
  label: "Files on desk now",
  fmt: "count",
  tone: "gold",
  live: true,
  info: "Credit files assigned to them and waiting on a decision right now. A live snapshot — it does not change with the period.",
  value: (r) => r.pendingNow,
};

const INTERACTIONS: Metric = {
  key: "interactions",
  label: "Calls / interactions",
  fmt: "count",
  calls: true,
  info: "Borrower interactions they logged on collection cases (calls, visits, messages).",
  value: (r) => col(r).interactions,
};

const CONTACT_RATE: Metric = {
  key: "contactRate",
  label: "Contact rate",
  fmt: "pct",
  info: "Share of their interactions where the borrower was reached — every outcome except “No answer”.",
  value: (r) => pct(col(r).interactions - col(r).noAnswer, col(r).interactions),
  ratio: { num: (r) => col(r).interactions - col(r).noAnswer, den: (r) => col(r).interactions },
};

const PTP: Metric = {
  key: "promisesToPay",
  label: "Promises to pay",
  fmt: "count",
  info: "Interactions they logged with a “Promise to pay” outcome.",
  value: (r) => col(r).promisesToPay,
};

const RECOVERED: Metric = {
  key: "recoveredPaise",
  label: "Recovered",
  fmt: "money",
  tone: "success",
  info: "Money from payments they recorded in the period that the Accountant has validated — i.e. actually credited to the loan.",
  value: (r) => col(r).paymentsRecoveredPaise,
};

const PAYMENTS_RECORDED: Metric = {
  key: "paymentsRaised",
  label: "Payments recorded",
  fmt: "count",
  info: "Collections payments they recorded in the period, whatever their status now.",
  value: (r) => col(r).paymentsRaised,
};

const PAYMENTS_PENDING: Metric = {
  key: "paymentsPending",
  label: "Awaiting validation",
  fmt: "count",
  info: "Of the payments they recorded in the period, those still waiting on the Collection Head or the Accountant.",
  value: (r) => col(r).paymentsPending,
};

const PAYMENTS_REJECTED: Metric = {
  key: "paymentsRejected",
  label: "Payments rejected",
  fmt: "count",
  tone: "error",
  info: "Of the payments they recorded in the period, those the Accountant rejected.",
  value: (r) => col(r).paymentsRejected,
};

const SETTLEMENTS_PROPOSED: Metric = {
  key: "settlementsProposed",
  label: "Settlements proposed",
  fmt: "count",
  info: "Settlements (full-and-final concessions) they proposed in the period.",
  value: (r) => col(r).settlementsProposed,
};

const CASES_NOW: Metric = {
  key: "casesNow",
  label: "Open cases now",
  fmt: "count",
  tone: "gold",
  live: true,
  info: "Open collection cases (loan not closed) assigned to them right now. A live snapshot — it does not change with the period.",
  value: (r) => col(r).casesNow,
};

const SETTLEMENTS_APPROVED: Metric = {
  key: "settlementsApproved",
  label: "Settlements approved",
  fmt: "count",
  tone: "success",
  info: "Settlements they approved as the checker in the period.",
  value: (r) => col(r).settlementsApproved,
};

const SETTLEMENTS_APPROVED_VALUE: Metric = {
  key: "settlementsApprovedPaise",
  label: "Value settled",
  fmt: "money",
  info: "Total settlement amount across the settlements they approved in the period.",
  value: (r) => col(r).settlementsApprovedPaise,
};

const SETTLEMENTS_REJECTED: Metric = {
  key: "settlementsRejected",
  label: "Settlements rejected",
  fmt: "count",
  tone: "error",
  info: "Settlements they turned down as the checker in the period.",
  value: (r) => col(r).settlementsRejected,
};

const RELEASED: Metric = {
  key: "released",
  label: "Disbursed",
  fmt: "count",
  tone: "success",
  info: "Disbursements they released with a transaction reference (plus admin force-releases) in the period.",
  value: (r) => act(r, "VALIDATE_SUCCESS", "DISB_ACCEPT", "ADMIN_FORCE_DISBURSE"),
};

const RELEASED_VALUE: Metric = {
  key: "releasedPaise",
  label: "Value disbursed",
  fmt: "money",
  info: "Total loan amount across the disbursements they released in the period.",
  value: (r) => r.releasedPaise ?? 0,
};

const REPAYMENTS_VERIFIED: Metric = {
  key: "verifiedCount",
  label: "Repayments verified",
  fmt: "count",
  tone: "success",
  info: "Borrower repayments they verified against the bank in the period.",
  value: (r) => r.verifiedCount ?? 0,
};

const REPAYMENTS_VERIFIED_VALUE: Metric = {
  key: "verifiedPaise",
  label: "Value verified",
  fmt: "money",
  info: "Total amount across the borrower repayments they verified in the period.",
  value: (r) => r.verifiedPaise ?? 0,
};

const REPAYMENTS_REJECTED: Metric = {
  key: "rejectedPaymentCount",
  label: "Repayments rejected",
  fmt: "count",
  tone: "error",
  info: "Borrower repayments they rejected in the period.",
  value: (r) => r.rejectedPaymentCount ?? 0,
};

const COLL_VALIDATED: Metric = {
  key: "paymentsValidated",
  label: "Collection payments validated",
  fmt: "count",
  tone: "success",
  info: "Payments recorded by collections staff that they validated and credited to the loan in the period.",
  value: (r) => col(r).paymentsValidated,
};

const COLL_VALIDATED_VALUE: Metric = {
  key: "paymentsValidatedPaise",
  label: "Collections value validated",
  fmt: "money",
  info: "Total amount across the collections payments they validated in the period.",
  value: (r) => col(r).paymentsValidatedPaise,
};

const COLL_VALIDATION_REJECTED: Metric = {
  key: "paymentsValidationRejected",
  label: "Collection payments rejected",
  fmt: "count",
  tone: "error",
  info: "Collections payments they rejected at validation in the period.",
  value: (r) => col(r).paymentsValidationRejected,
};

const LEAD_CALLS: Metric = {
  key: "leadCalls",
  label: "Lead calls",
  fmt: "count",
  calls: true,
  info: "Calls they logged against customers and leads from the telecalling list.",
  value: leadCalls,
};

const ALL_CALLS: Metric = {
  key: "callsMade",
  label: "Calls",
  fmt: "count",
  calls: true,
  info: "Telecalling plus collections calls they logged in the period.",
  value: (r) => r.callsMade,
};

// ---- entities ----------------------------------------------------------------------------------

export const PERFORMANCE_ENTITIES: PerformanceEntity[] = [
  {
    key: "CREDIT_EXECUTIVE",
    label: "Credit Executive",
    blurb: "Files decided: sanctions, rejections and parked files, how fast, and how much was sanctioned.",
    roles: ["CREDIT_EXECUTIVE"],
    metrics: [
      SANCTIONED,
      CREDIT_REJECTED,
      {
        key: "parked",
        label: "Parked",
        fmt: "count",
        info: "Files they marked pending to ask the borrower for a document, in the period.",
        value: (r) => act(r, "MARK_PENDING"),
      },
      {
        key: "approvalRate",
        label: "Approval rate",
        fmt: "pct",
        info: "Sanctioned ÷ (sanctioned + rejected) — of the files they closed out, the share they approved.",
        value: (r) => pct(act(r, "SANCTION"), act(r, "SANCTION", "REJECT_LEAD", "KYC_REJECT")),
        ratio: { num: (r) => act(r, "SANCTION"), den: (r) => act(r, "SANCTION", "REJECT_LEAD", "KYC_REJECT") },
      },
      SANCTIONED_VALUE,
      TURNAROUND,
      ON_CREDIT_DESK,
      ACTIVE_DAYS,
    ],
    tiles: ["sanctioned", "creditRejected", "parked", "sanctionedPaise", "approvalRate", "pendingNow"],
    defaultSort: "sanctioned",
  },
  {
    key: "CREDIT_HEAD",
    label: "Credit Head",
    blurb: "Routing the credit desk: files assigned and reassigned, leads rejected, and files decided personally.",
    roles: ["CREDIT_HEAD"],
    metrics: [
      {
        key: "assigned",
        label: "Assigned",
        fmt: "count",
        tone: "success",
        info: "Credit files they assigned to an executive (or to themselves) in the period.",
        value: (r) => act(r, "ASSIGN"),
      },
      {
        key: "reassigned",
        label: "Reassigned",
        fmt: "count",
        info: "Credit files they moved from one executive to another in the period.",
        value: (r) => act(r, "REASSIGN"),
      },
      CREDIT_REJECTED,
      SANCTIONED,
      SANCTIONED_VALUE,
      TURNAROUND,
      ON_CREDIT_DESK,
      TOTAL_ACTIONS,
      ACTIVE_DAYS,
    ],
    tiles: ["assigned", "reassigned", "creditRejected", "sanctioned", "pendingNow"],
    defaultSort: "assigned",
  },
  {
    key: "COLLECTION_EXECUTIVE",
    label: "Collection Executive",
    blurb: "Working overdue cases: calls and who was reached, promises to pay, payments recorded and money recovered.",
    roles: ["COLLECTION_EXECUTIVE"],
    metrics: [
      INTERACTIONS,
      CONTACT_RATE,
      PTP,
      PAYMENTS_RECORDED,
      RECOVERED,
      PAYMENTS_PENDING,
      PAYMENTS_REJECTED,
      SETTLEMENTS_PROPOSED,
      CASES_NOW,
      ACTIVE_DAYS,
    ],
    tiles: ["interactions", "contactRate", "promisesToPay", "recoveredPaise", "paymentsRaised", "casesNow"],
    defaultSort: "interactions",
  },
  {
    key: "COLLECTION_HEAD",
    label: "Collection Head",
    blurb: "Checking the collections desk: settlements approved or rejected, plus the cases and calls they worked themselves.",
    roles: ["COLLECTION_HEAD"],
    metrics: [
      SETTLEMENTS_APPROVED,
      SETTLEMENTS_APPROVED_VALUE,
      SETTLEMENTS_REJECTED,
      INTERACTIONS,
      PTP,
      PAYMENTS_RECORDED,
      RECOVERED,
      CASES_NOW,
      ACTIVE_DAYS,
    ],
    tiles: ["settlementsApproved", "settlementsApprovedPaise", "settlementsRejected", "recoveredPaise", "casesNow"],
    defaultSort: "settlementsApproved",
  },
  {
    key: "DISBURSEMENT_HEAD",
    label: "Disbursement Head",
    blurb: "Releasing sanctioned loans: transfers made, value released, rejections and retries.",
    roles: ["DISBURSEMENT_HEAD"],
    metrics: [
      RELEASED,
      RELEASED_VALUE,
      {
        key: "disbRejected",
        label: "Rejected",
        fmt: "count",
        tone: "error",
        info: "Disbursements they rejected in the period.",
        value: (r) => act(r, "DISB_REJECT"),
      },
      {
        key: "retries",
        label: "Retries",
        fmt: "count",
        info: "Failed transfers they retried in the period.",
        value: (r) => act(r, "RETRY"),
      },
      {
        key: "cancelled",
        label: "Cancelled",
        fmt: "count",
        info: "Files they cancelled in the period.",
        value: (r) => act(r, "CANCEL"),
      },
      TOTAL_ACTIONS,
      ACTIVE_DAYS,
    ],
    tiles: ["released", "releasedPaise", "disbRejected", "retries"],
    defaultSort: "released",
  },
  {
    key: "ACCOUNTANT",
    label: "Accountant",
    blurb: "Confirming money in: borrower repayments and collections payments verified or rejected.",
    roles: ["ACCOUNTANT"],
    metrics: [
      REPAYMENTS_VERIFIED,
      REPAYMENTS_VERIFIED_VALUE,
      REPAYMENTS_REJECTED,
      COLL_VALIDATED,
      COLL_VALIDATED_VALUE,
      COLL_VALIDATION_REJECTED,
      ACTIVE_DAYS,
    ],
    tiles: ["verifiedCount", "verifiedPaise", "rejectedPaymentCount", "paymentsValidated", "paymentsValidatedPaise"],
    defaultSort: "verifiedCount",
  },
  {
    key: "TELECALLER",
    label: "Telecaller",
    blurb: "Calling the lead list: calls logged and the days they were on the phones.",
    roles: ["TELECALLER"],
    metrics: [LEAD_CALLS, ACTIVE_DAYS],
    tiles: ["leadCalls", "activeDays"],
    defaultSort: "leadCalls",
  },
  {
    key: "ADMIN",
    label: "Admin",
    blurb: "Admins working other roles: whatever they decided, released, settled or called.",
    roles: ["ADMIN"],
    metrics: [SANCTIONED, CREDIT_REJECTED, RELEASED, SETTLEMENTS_APPROVED, ALL_CALLS, TOTAL_ACTIONS, ACTIVE_DAYS],
    tiles: ["sanctioned", "released", "settlementsApproved", "totalActions"],
    defaultSort: "totalActions",
  },
  {
    key: "ALL",
    label: "All staff",
    blurb: "Everyone in your scope on one sheet — open a person for the figures that matter for their role.",
    roles: [],
    metrics: [
      TOTAL_ACTIONS,
      ALL_CALLS,
      {
        key: "moneyPaise",
        label: "Value moved",
        fmt: "money",
        info: "Total value they sanctioned or released in the period.",
        value: (r) => r.moneyPaise,
      },
      RECOVERED,
      ACTIVE_DAYS,
    ],
    tiles: ["totalActions", "callsMade", "moneyPaise", "recoveredPaise"],
    defaultSort: "totalActions",
  },
];

export function entityFor(key: string | null | undefined): PerformanceEntity | undefined {
  return PERFORMANCE_ENTITIES.find((e) => e.key === key);
}

/** The entity whose figures describe this staffer's own job — the pop-up leads with it. */
export function entityForRole(role: StaffRoleName): PerformanceEntity {
  return PERFORMANCE_ENTITIES.find((e) => e.roles.includes(role)) ?? entityFor("ALL")!;
}

export function rowsForEntity(rows: readonly StaffPerformanceRow[], entity: PerformanceEntity): StaffPerformanceRow[] {
  return entity.roles.length === 0 ? [...rows] : rows.filter((r) => entity.roles.includes(r.role));
}

/**
 * The switcher's options: only entities with someone in the roster (a Credit Head is not offered a
 * Collection tab with nobody in it), each with its head-count; "All staff" whenever the roster spans
 * more than one entity.
 */
export function availableEntities(rows: readonly StaffPerformanceRow[]): Array<{ entity: PerformanceEntity; count: number }> {
  const out = PERFORMANCE_ENTITIES.filter((e) => e.roles.length > 0)
    .map((entity) => ({ entity, count: rowsForEntity(rows, entity).length }))
    .filter((o) => o.count > 0);
  if (out.length > 1) out.push({ entity: entityFor("ALL")!, count: rows.length });
  return out;
}

/** `?entity=` if it is on offer, else the first one that is (or null with nobody to show). */
export function resolveEntity(requested: string | null | undefined, rows: readonly StaffPerformanceRow[]): PerformanceEntity | null {
  const options = availableEntities(rows);
  return options.find((o) => o.entity.key === requested)?.entity ?? options[0]?.entity ?? null;
}

/** A row flattened for the table: every metric of the entity as a sortable property. */
export type PerformanceViewRow = {
  staffId: number;
  staffName: string;
  role: StaffRoleName;
  active: boolean;
  firstActionAt: string | null;
  lastActionAt: string | null;
  raw: StaffPerformanceRow;
} & Record<string, unknown>;

export function toViewRows(rows: readonly StaffPerformanceRow[], entity: PerformanceEntity): PerformanceViewRow[] {
  return rows.map((r) => {
    const v: PerformanceViewRow = {
      staffId: r.staffId,
      staffName: r.staffName,
      role: r.role,
      active: r.active,
      firstActionAt: r.firstActionAt,
      lastActionAt: r.lastActionAt,
      raw: r,
    };
    for (const m of entity.metrics) v[m.key] = m.value(r);
    return v;
  });
}

/**
 * A metric totalled over the rows on screen: rates as Σnum/Σden, turnaround as the mean of the rows
 * that have one, everything else summed. `null` when there are no rows to total (still loading), or
 * nothing to divide by — the tile then reads "—", never a 0 nobody measured.
 */
export function metricTotal(metric: Metric, rows: readonly StaffPerformanceRow[] | undefined): number | null {
  if (!rows) return null;
  if (metric.ratio) {
    const num = rows.reduce((n, r) => n + metric.ratio!.num(r), 0);
    const den = rows.reduce((n, r) => n + metric.ratio!.den(r), 0);
    return pct(num, den);
  }
  if (metric.fmt === "minutes") {
    const vals = rows.map(metric.value).filter((v): v is number => v != null);
    return vals.length ? Math.round(vals.reduce((a, b) => a + b, 0) / vals.length) : null;
  }
  return rows.reduce((n, r) => n + (metric.value(r) ?? 0), 0);
}

/** The roster-wide trend rebuilt from the per-person series, so it follows the entity + filters. */
export function dailyFor(rows: readonly StaffPerformanceRow[]): Array<{ date: string; actions: number }> {
  const byDay = new Map<string, number>();
  for (const r of rows) for (const p of r.daily ?? []) byDay.set(p.date, (byDay.get(p.date) ?? 0) + p.actions);
  return [...byDay.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([date, actions]) => ({ date, actions }));
}

/** Human labels for the action breakdown in the pop-up; unknown actions fall back to the raw code. */
export const ACTION_LABEL: Record<string, string> = {
  ASSIGN: "Assigned a file",
  REASSIGN: "Reassigned a file",
  SANCTION: "Sanctioned",
  REJECT_LEAD: "Rejected a lead",
  MARK_PENDING: "Parked (asked for a document)",
  KYC_APPROVE: "Approved KYC",
  KYC_REJECT: "Rejected KYC",
  VALIDATE_SUCCESS: "Released a disbursement",
  DISB_ACCEPT: "Accepted a disbursement",
  DISB_REJECT: "Rejected a disbursement",
  RETRY: "Retried a disbursement",
  CANCEL: "Cancelled a file",
  ADMIN_FORCE_DISBURSE: "Forced to disbursement",
};

/** Minutes → "3h 20m" / "45m" / "2d 4h" — a bare minute count is unreadable past an hour. */
export function humanMinutes(mins: number | null): string {
  if (mins == null) return "—";
  if (mins < 60) return `${mins}m`;
  if (mins < 60 * 24) {
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m ? `${h}h ${m}m` : `${h}h`;
  }
  const d = Math.floor(mins / (60 * 24));
  const h = Math.floor((mins % (60 * 24)) / 60);
  return h ? `${d}d ${h}h` : `${d}d`;
}

/** A metric value for display; null is always "—". Money of 0 is "—" too: nothing moved. */
export function formatMetric(fmt: MetricFormat, v: number | null): string {
  if (v == null) return "—";
  switch (fmt) {
    case "money":
      return v ? paiseToINR(v) : "—";
    case "minutes":
      return humanMinutes(v);
    case "pct":
      return `${v}%`;
    default:
      return v.toLocaleString("en-IN");
  }
}
