/**
 * Pure helpers behind the staff customer 360 page (`/staff/customers/[customerId]`) and its shared
 * tabs (`components/staff/customer-tabs.tsx`). Kept out of the components so each rule has a unit
 * test and the page and the list dialog cannot compute them two different ways.
 */

import { daysBetween } from "@/lib/calc/loan-math";
import type { ActivityEntry, CustomerDetail } from "@/lib/api/applications";

// ---------------------------------------------------------------------------
// IST calendar day + the salary-linked due-date window
// ---------------------------------------------------------------------------

/** IST is a fixed UTC+05:30 — India observes no daylight saving, so a constant offset is exact. */
const IST_OFFSET_MS = (5 * 60 + 30) * 60 * 1000;

/**
 * Today's IST calendar date as a local-midnight `Date` (its getFullYear/getMonth/getDate are the
 * IST day). The backend derives business dates from `LocalDate.now(IST)`; the browser's own zone is
 * not a business date, so a staffer outside India must not project from the wrong day.
 *
 * The local-midnight shape is what `dueDateFromSalary` and `daysBetween` read (they compare local
 * date fields), so the result can be handed straight to them.
 */
export function istCalendarToday(now: Date = new Date()): Date {
  const ist = new Date(now.getTime() + IST_OFFSET_MS);
  return new Date(ist.getUTCFullYear(), ist.getUTCMonth(), ist.getUTCDate());
}

/**
 * The salary-linked due date must fall within this many days of disbursal (CLAUDE.md §9, backend
 * `LoanMath.MAX_TERM_DAYS`). Mirrored here because `loan-math.ts` keeps its copy private.
 */
export const SALARY_DUE_MAX_DAYS = 40;

/**
 * How far a projected due date sits from the disbursal it was projected from, and whether that
 * breaks the 40-day rule. Calendar days, time of day ignored.
 */
export function salaryDueWindow(
  disbursedOn: Date,
  dueDate: Date,
  maxDays: number = SALARY_DUE_MAX_DAYS,
): { days: number; exceeds: boolean } {
  const days = daysBetween(disbursedOn, dueDate);
  return { days, exceeds: days > maxDays };
}

/**
 * A `yyyy-mm-dd` business date (a backend `LocalDate`) as a local-midnight `Date`, or null when the
 * string is not one. `new Date("2026-10-03")` parses as UTC midnight, which a browser west of UTC
 * then shows as the 2nd — this keeps the calendar day the backend meant.
 */
export function isoDayToLocalDate(iso: string | null | undefined): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? "");
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(d.getTime()) ? null : d;
}

// ---------------------------------------------------------------------------
// Eligible-limit basis
// ---------------------------------------------------------------------------

export type LimitBasis = "ADMIN_OVERRIDE" | "SALARY_RULE";

export const LIMIT_BASIS_LABEL: Record<LimitBasis, string> = {
  ADMIN_OVERRIDE: "Admin override",
  SALARY_RULE: "Salary rule",
};

/**
 * Where a customer's eligible limit comes from: an ADMIN-set override when one is stored on the
 * customer (`limitOverridePaise`), otherwise the 25%-of-monthly-salary rule.
 */
export function limitBasisOf(overridePaise: number | null | undefined): LimitBasis {
  return overridePaise != null ? "ADMIN_OVERRIDE" : "SALARY_RULE";
}

// ---------------------------------------------------------------------------
// Rupee input preview
// ---------------------------------------------------------------------------

/**
 * Live preview for a whole-rupee input: the digits typed, grouped the Indian way ("₹ 4,20,000"),
 * or null when nothing has been typed. Works on the digit string itself rather than through
 * `Number`, so a long paste is never rounded in the preview. Display only — callers keep submitting
 * the raw field value exactly as before.
 */
export function rupeeInputPreview(raw: string | null | undefined): string | null {
  const digits = (raw ?? "").replace(/\D/g, "").replace(/^0+(?=\d)/, "");
  if (digits === "") return null;
  if (digits.length <= 3) return `₹ ${digits}`;
  const lastThree = digits.slice(-3);
  const rest = digits.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",");
  return `₹ ${rest},${lastThree}`;
}

// ---------------------------------------------------------------------------
// Loans exposure summary
// ---------------------------------------------------------------------------

export interface CustomerExposure {
  loanCount: number;
  /** Σ principal over every loan on the customer (closed ones included). */
  totalPrincipalPaise: number;
  /**
   * Σ the compute-on-read outstanding from `outstandingByLoanId`. Null when the payload does not
   * carry a figure for every loan — a partial sum would understate what is owed.
   */
  totalOutstandingPaise: number | null;
  /** Latest `paidOn` among VERIFIED payments only (a pending or rejected one is not money in). */
  lastVerifiedPaymentOn: string | null;
}

export function customerExposure(
  detail: Pick<CustomerDetail, "loans" | "payments" | "outstandingByLoanId">,
): CustomerExposure {
  const loans = detail.loans ?? [];
  const totalPrincipalPaise = loans.reduce((sum, loan) => sum + (loan.principalPaise ?? 0), 0);

  let totalOutstandingPaise: number | null = null;
  const byLoan = detail.outstandingByLoanId;
  if (byLoan && loans.length > 0) {
    let sum = 0;
    let complete = true;
    for (const loan of loans) {
      const entry = byLoan[String(loan.id)];
      if (entry == null || typeof entry.outstandingPaise !== "number") {
        complete = false;
        break;
      }
      sum += entry.outstandingPaise;
    }
    totalOutstandingPaise = complete ? sum : null;
  }

  let lastVerifiedPaymentOn: string | null = null;
  for (const payment of detail.payments ?? []) {
    if (payment.status !== "VERIFIED" || !payment.paidOn) continue;
    // ISO yyyy-mm-dd compares correctly as a string.
    if (lastVerifiedPaymentOn == null || payment.paidOn > lastVerifiedPaymentOn) {
      lastVerifiedPaymentOn = payment.paidOn;
    }
  }

  return { loanCount: loans.length, totalPrincipalPaise, totalOutstandingPaise, lastVerifiedPaymentOn };
}

// ---------------------------------------------------------------------------
// Audit-log filter chips
// ---------------------------------------------------------------------------

export const AUDIT_FILTER_ALL = "ALL";

/** Staff-facing names for the activity feed's `type` (backend `CustomerService.activity`). */
export const AUDIT_TYPE_LABEL: Record<string, string> = {
  LIFECYCLE: "Lifecycle",
  REVERIFY: "Re-verify",
  PROFILE: "Profile edit",
  REMARK: "Remark",
  VERIFICATION: "Verification",
  REFERENCE: "Reference",
  UPLOAD: "Upload",
  CALL: "Call",
};

/** Chip order: the known types in this order, then any type the backend adds later, A→Z. */
const AUDIT_TYPE_ORDER = Object.keys(AUDIT_TYPE_LABEL);

export function auditTypeLabel(type: string): string {
  return AUDIT_TYPE_LABEL[type] ?? type;
}

export interface AuditChip {
  type: string;
  label: string;
  count: number;
}

/** One chip per event type actually present in the loaded feed, with its count. */
export function auditTypeChips(items: Pick<ActivityEntry, "type">[]): AuditChip[] {
  const counts = new Map<string, number>();
  for (const item of items) counts.set(item.type, (counts.get(item.type) ?? 0) + 1);
  const rank = (type: string) => {
    const i = AUDIT_TYPE_ORDER.indexOf(type);
    return i === -1 ? AUDIT_TYPE_ORDER.length : i;
  };
  return Array.from(counts.entries())
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([type, count]) => ({ type, label: auditTypeLabel(type), count }));
}

/** The feed narrowed to one type; "ALL" returns it unchanged. */
export function filterActivityByType<T extends Pick<ActivityEntry, "type">>(items: T[], type: string): T[] {
  if (type === AUDIT_FILTER_ALL) return items;
  return items.filter((item) => item.type === type);
}

/**
 * The chip actually in force: the one selected, unless a refetch removed every event of that type
 * — then "All", so the reader is never left on an empty filter with no chip lit to explain it.
 */
export function resolveAuditFilter(chips: AuditChip[], selected: string): string {
  if (selected === AUDIT_FILTER_ALL) return AUDIT_FILTER_ALL;
  return chips.some((chip) => chip.type === selected) ? selected : AUDIT_FILTER_ALL;
}
