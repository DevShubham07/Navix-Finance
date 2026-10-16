"use client";

/**
 * The column-filter wiring every application-queue panel shares, in one place so five queues, the
 * workbench groups and the repayment columns all filter identically.
 *
 * Filtering lives ABOVE the bulk selection (see `useQueueRegister`'s `visibleIds`): "Select all"
 * must mean the rows the filter is showing, or a bulk reject — a lifecycle action with a 30-day
 * borrower cooling-off — would hit applications the operator cannot see.
 */

import { paiseToINR, type ApplicationView } from "@/lib/api/applications";
import { useStaffMe } from "@/components/staff/pipeline/hooks";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { thBag } from "@/lib/staff/register-th";

/** Identity (ids, mobile, PAN, account, IFSC), the Signals stars and Actions are deliberately absent:
 *  a dropdown of account numbers is not a feature, and a star glyph has no single scalar behind it. */
export const QUEUE_SORT_KEYS = [
  "createdAt", "customerName", "amountRequestedPaise", "loanDueDate", "creditScore", "status",
  "creditDecidedByName", "disbursedByName", "collectionOfficerName",
] as const;

export const QUEUE_COLUMN_LABEL: Record<string, string> = {
  createdAt: "Date",
  customerName: "Customer",
  amountRequestedPaise: "Amount",
  loanDueDate: "Due",
  creditScore: "Credit",
  status: "Stage",
  creditDecidedByName: "Credit exec",
  disbursedByName: "Disbursed by",
  collectionOfficerName: "Collections exec",
};

/** The amount the row shows: requested, else the eligible limit (mirrors AppRow). Sorting reads the
 *  stored `amountRequestedPaise` field itself (`sortRows` indexes row[key]), so a limit-only row sorts as blank. */
const amountPaise = (a: ApplicationView) => a.amountRequestedPaise ?? a.eligibleLimitPaise ?? null;

/** Stage as the row displays it: an overdue/in-collections loan outranks the aggregate's ACTIVE. */
const stageRaw = (a: ApplicationView) =>
  a.loanStatus === "OVERDUE" || a.loanStatus === "IN_COLLECTIONS" ? a.loanStatus : a.status;
const humanise = (s: string) => s.toLowerCase().split("_").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ");

// The staff row carries only the NAME of who decided / released / chased a file, not an id, so the
// name is the key here. A blank is a value people filter for, so it is a tickable "Unassigned".
const person = (key: "creditDecidedByName" | "disbursedByName" | "collectionOfficerName"): FilterColumn<ApplicationView> => ({
  key,
  value: (a) => a[key] || "Unassigned",
  raw: (a) => a[key] || "",
});

export const QUEUE_FILTER_COLUMNS: Array<FilterColumn<ApplicationView>> = [
  { key: "createdAt", kind: "date", value: (a) => istDay(a.createdAt) ?? "—", date: (a) => istDay(a.createdAt) },
  { key: "customerName", kind: "text", value: (a) => a.customerName ?? "" },
  {
    key: "amountRequestedPaise",
    kind: "num",
    value: (a) => (amountPaise(a) != null ? paiseToINR(amountPaise(a)!) : "—"),
    // Rupees: the bound is typed beside a rupee column.
    num: (a) => (amountPaise(a) != null ? amountPaise(a)! / 100 : null),
  },
  { key: "loanDueDate", kind: "date", value: (a) => istDay(a.loanDueDate) ?? "—", date: (a) => istDay(a.loanDueDate) },
  { key: "creditScore", kind: "num", value: (a) => (a.creditScore != null ? String(a.creditScore) : "—"), num: (a) => a.creditScore ?? null },
  { key: "status", value: (a) => humanise(stageRaw(a)), raw: stageRaw },
  person("creditDecidedByName"),
  person("disbursedByName"),
  person("collectionOfficerName"),
];

/**
 * One queue panel's view. `registerId` namespaces its remembered view and must stay stable.
 * Call this BEFORE `useBulkQueue` and hand that `visibleIds`.
 */
export function useQueueRegister(apps: ApplicationView[], registerId: string) {
  const staffId = useStaffMe().data?.id;
  const rq = useRegisterQuery({
    registerId,
    staffId: staffId,
    sortKeys: QUEUE_SORT_KEYS,
    filterKeys: QUEUE_SORT_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
    // /staff/applications mounts several of these at once; shared URL params would cross-talk.
    url: false,
  });
  const cr = useClientRegister(apps, QUEUE_FILTER_COLUMNS, rq);
  const th = thBag(rq, cr, QUEUE_FILTER_COLUMNS, QUEUE_COLUMN_LABEL);
  return { rq, cr, th, visibleIds: cr.rows.map((a) => a.id) };
}

export type QueueRegister = ReturnType<typeof useQueueRegister>;
