"use client";

import * as React from "react";
import Link from "next/link";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, Contact, ChevronDown, ChevronRight as ChevronRightIcon, UserPlus, X as XIcon, Pencil } from "lucide-react";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { Badge, EmptyState, ErrorState, Skeleton, StatusBadge } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import {
  PermissionGate,
  NoAccessNotice,
  useStaffMe, useCan,
  useQueueSelection,
  useBulkRoleFlags,
  RejectDialog,
  AssignDialog,
  BulkActionBar,
  MIXED_REJECT_MODES_REASON,
  type RejectMode,
} from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { CreditBadge } from "@/components/staff/credit-badge";
import { bureauStateLabel, reportWithoutScoreLabel } from "@/components/staff/bureau-state";
import {
  SEVERITY_BADGE_VARIANT,
  caseFailureLabel,
  isNoFailure,
  type CaseFailureReason,
  type CaseFailureSeverity,
} from "@/components/staff/case-failure";
import { CaseFailureDialog } from "@/components/staff/case-failure-dialog";
import { CustomerEditDialog } from "@/components/staff/customer-edit-dialog";
import { CustomerDetailDialog } from "@/components/staff/customer-detail-dialog";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import {
  customersApi,
  paiseToINR,
  statusLabel,
  type CustomerSummary,
  type CustomerSummaryCounts,
  type ApplicationStatus,
} from "@/lib/api/applications";
import { AmountCell, DueCell, dpdFor } from "@/components/staff/pipeline/cells";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { formatDate, formatDateTime } from "@/lib/utils";
import { SEGMENTS, SEGMENT_LABEL, type CustomerSegment } from "@/lib/customers/segments";
import {
  customerRowTarget,
  dateWindowChanged,
  type CustomerRowTarget,
} from "@/lib/customers/customers-register";

const ZERO_COUNTS: CustomerSummaryCounts = {
  all: 0,
  incomplete: 0,
  pending: 0,
  review: 0,
  approved: 0,
  disbursementPending: 0,
  active: 0,
  overdue: 0,
  hold: 0,
  rejected: 0,
  closed: 0,
  unallocated: 0,
};

/**
 * Statuses the backend's transition map allows REJECTED from (mirrors `ApplicationStatus.
 * canTransitionTo` — see CLAUDE.md §5/§11). Anything else (ACTIVE/CLOSED/REJECTED/CANCELLED/
 * OVERDUE/DEFAULTED/WRITTEN_OFF/DISBURSED/…) has nothing left to reject.
 */
const REJECTABLE_STATUSES = new Set<ApplicationStatus>([
  "DRAFT",
  "KYC_PENDING",
  "KYC_APPROVED",
  "REVIEW_PENDING",
  "CREDIT_EXEC_PENDING",
  "SANCTIONED",
  "DISBURSEMENT_PENDING",
]);

/** DISBURSEMENT_PENDING rejects through the disbursement-decision call; every other rejectable
 *  stage (credit review, KYC, reborrow review, sanctioned) rejects through reject-lead. */
function rejectModeFor(status: string | null | undefined): RejectMode | null {
  if (!status || !REJECTABLE_STATUSES.has(status as ApplicationStatus)) return null;
  return status === "DISBURSEMENT_PENDING" ? "disbursement" : "credit";
}

/** Why a row's checkbox/actions are disabled — surfaced as the checkbox `title`. */
function notActionableReason(c: CustomerSummary): string | null {
  if (c.latestApplicationId == null) return "No application on this customer to act on";
  if (rejectModeFor(c.latestStatus) != null) return null;
  switch (c.latestStatus) {
    case "REJECTED":
      return "Already rejected";
    case "CANCELLED":
      return "Application already cancelled";
    case "CLOSED":
      return "Loan is closed — nothing to reject";
    case "KYC_REJECTED":
      return "Already rejected";
    default:
      return "Loan is active — nothing to reject";
  }
}

/**
 * Customers — a borrower-centric roll-up across the loan aggregate. Search (name, PAN, mobile,
 * customer or application id), the date window, the segment chip (?seg=), "mine" (?mine=1) and
 * paging are all applied server-side; the chip counts come from a separate, cheaper summary call
 * that is allowed to lag the table by up to 30s.
 */
export default function CustomersPage() {
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded bg-grey-100" />}>
      <CustomersPageInner />
    </React.Suspense>
  );
}

function CustomersPageInner() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const segParam = (searchParams.get("seg") ?? "all") as CustomerSegment;
  const seg: CustomerSegment =
    segParam === "all" || SEGMENTS.includes(segParam) ? segParam : "all";
  const mine = searchParams.get("mine") === "1";
  const me = useStaffMe().data;
  const can = useCan();
  const qc = useQueryClient();

  // Deep link from the global-search palette's "View all" link (`?q=…`).
  const [query, setQuery] = React.useState(searchParams.get("q") ?? "");
  // "Open" goes straight to the row's latest application when it has one; only a customer with
  // no application on file falls back to the customer view (see customerRowTarget).
  const [openTarget, setOpenTarget] = React.useState<CustomerRowTarget | null>(null);
  // Carries the name too, so the failure dialog can title itself without a second fetch.
  const [failureCustomer, setFailureCustomer] =
    React.useState<{ id: number; name: string | null } | null>(null);
  const [editCustomerId, setEditCustomerId] = React.useState<number | null>(null);
  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = React.useMemo(() => rangeFor(period, custom), [period, custom]);

  // Heads + ADMIN see the whole book; everyone else is scoped server-side by CustomerService to
  // customers assigned to them or that they've decided on. This only drives the notice below —
  // the enforcement is the backend's.
  const fullView = me?.role ? can("customer:view:all") : true;

  // Any filter change lands on page 1 — page 7 of a different result set is meaningless. The reset
  // happens in the same update as the filter change (never in an effect afterwards), so the list
  // query is never asked for the old page number under the new filter.
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSizeState] = React.useState(25);
  const setPageSize = React.useCallback((n: number) => {
    setPageSizeState(n);
    setPage(1);
  }, []);
  const changePeriod = (next: QueuePeriod) => {
    if (dateWindowChanged(range, rangeFor(next, custom))) setPage(1);
    setPeriod(next);
  };
  const changeCustom = (next: QueueRange) => {
    if (dateWindowChanged(range, rangeFor(period, next))) setPage(1);
    setCustom(next);
  };
  // `seg` and `mine` live in the URL and also change from outside this page's handlers (the sidebar's
  // segment links, back/forward). A setPage(1) inside setSeg would not help either: router.replace
  // commits the new URL only after Next resolves the navigation, so the reset would land first and
  // fetch the OLD segment's page 1. Instead the reset is applied during the render that first sees
  // the new URL values; React discards that render and re-runs it with page 1 before committing, so
  // no query is ever issued for the stale page.
  const urlScope = `${seg}|${mine ? "mine" : "all"}`;
  const [pagedScope, setPagedScope] = React.useState(urlScope);
  if (pagedScope !== urlScope) {
    setPagedScope(urlScope);
    setPage(1);
  }

  const baseFilters = React.useMemo(
    () => ({ q: query || undefined, from: range.from, to: range.to, mine: mine || undefined }),
    [query, range.from, range.to, mine],
  );

  // The range MUST be in the key — without it React Query serves the previous window's rows
  // when the filter changes. Normalised to "" because undefined is not a stable key boundary.
  const listQ = useQuery({
    queryKey: ["customers-page", query, range.from ?? "", range.to ?? "", seg, mine, page, pageSize],
    queryFn: () =>
      customersApi.page({ ...baseFilters, seg: seg === "all" ? undefined : seg, page, size: pageSize }),
    // Keep the previous page on screen while the next one loads instead of flashing a skeleton.
    placeholderData: keepPreviousData,
  });
  // Chip counts are a separate, cheap aggregate. Up to 30s stale is fine here (the table itself is
  // always fresh), and it stops every keystroke re-counting the whole book.
  const summaryQ = useQuery({
    queryKey: ["customers-summary", query, range.from ?? "", range.to ?? "", mine],
    queryFn: () => customersApi.summary(baseFilters),
    staleTime: 30_000,
  });
  const refreshAll = () => {
    listQ.refetch();
    summaryQ.refetch();
  };
  const invalidateAll = () => {
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    qc.invalidateQueries({ queryKey: ["customers-summary"] });
  };

  const pageRows = React.useMemo(() => listQ.data?.rows ?? [], [listQ.data]);
  const total = listQ.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const counts: CustomerSummaryCounts = summaryQ.data ?? ZERO_COUNTS;
  const [collapsedDates, setCollapsedDates] = React.useState<Set<string>>(new Set());
  const toggleDate = (key: string) =>
    setCollapsedDates((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  // Consecutive rows on the current page grouped by their stage-change (statusChangedAt) date —
  // the same key the API sorts by, so a run of consecutive rows really does share one date.
  const dateGroups = React.useMemo(() => {
    const list: { key: string; rows: CustomerSummary[] }[] = [];
    for (const c of pageRows) {
      const key = c.statusChangedAt ? c.statusChangedAt.slice(0, 10) : "unknown";
      const last = list[list.length - 1];
      if (last && last.key === key) last.rows.push(c);
      else list.push({ key, rows: [c] });
    }
    return list;
  }, [pageRows]);

  // --- Bulk assign / bulk reject (Task 1/2) ---------------------------------------------------
  // The bulk-action target is each row's LATEST application, not the customer id — matching the
  // pipeline queues, which act on applications. Only rows whose latest application is still in a
  // rejectable stage are selectable at all, so select-all can never sweep an already-decided or
  // live-loan row into a reject/assign run. Scoped to the page on screen: "select all" means the
  // rows the staffer can see, never the thousands behind the pagination.
  const { canBulkReject, canBulkAssign } = useBulkRoleFlags();
  // Row-level Reject only (not bulk select / assign): ADMIN may also reject a PRE_APPROVED lead — the
  // manual clean-up for pre-approvals the old reborrow gap minted for customers with no repaid loan.
  // The backend allows PRE_APPROVED → REJECTED for ADMIN alone (ApplicationFlowService.rejectLead).
  const rowRejectMode = (c: CustomerSummary): RejectMode | null =>
    rejectModeFor(c.latestStatus) ??
    (me?.role === "ADMIN" && c.latestStatus === "PRE_APPROVED" ? "credit" : null);
  const actionableRows = React.useMemo(
    () => pageRows.filter((c) => c.latestApplicationId != null && notActionableReason(c) == null),
    [pageRows],
  );
  const actionableIds = React.useMemo(
    () => actionableRows.map((c) => c.latestApplicationId as number),
    [actionableRows],
  );
  const modeById = React.useMemo(() => {
    const m = new Map<number, RejectMode>();
    for (const c of actionableRows) {
      const mode = rejectModeFor(c.latestStatus);
      if (c.latestApplicationId != null && mode) m.set(c.latestApplicationId, mode);
    }
    return m;
  }, [actionableRows]);
  const sel = useQueueSelection(actionableIds);
  const selectedIds = React.useMemo(() => [...sel.selected], [sel.selected]);
  const selectedModes = React.useMemo(
    () => new Set(selectedIds.map((id) => modeById.get(id)).filter((m): m is RejectMode => m != null)),
    [selectedIds, modeById],
  );
  // A bulk reject run loops exactly one endpoint (rejectLead vs. disbursementDecision — see
  // `RejectDialog`). Mixing a DISBURSEMENT_PENDING row into the same selection as a credit-stage
  // row leaves no single mode to run it under, so rather than silently splitting one click into
  // two separate reason prompts, the bulk Reject action just requires a single-mode selection —
  // the bar disables Reject with a tooltip when the selection spans both.
  const mixedRejectModes = selectedModes.size > 1;
  const bulkRejectMode: RejectMode | undefined = selectedModes.size === 1 ? [...selectedModes][0] : undefined;

  const [pendingReject, setPendingReject] = React.useState<{ ids: number[]; mode: RejectMode } | null>(null);
  const [pendingAssign, setPendingAssign] = React.useState<number[] | null>(null);
  const showBulkColumn = canBulkReject || canBulkAssign;
  // +1 for the Failure column. The date-group header rows span the whole table with this.
  const colCount = showBulkColumn ? 22 : 21;

  function setSeg(next: CustomerSegment) {
    const p = new URLSearchParams(searchParams.toString());
    if (next === "all") p.delete("seg");
    else p.set("seg", next);
    const qs = p.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
  }

  return (
    <div>
      <PageHeader title="Customers" subtitle="Every borrower — search by name or ID, then open to see loans, payments and KYC.">
        <ExportMenu
          title="Customers"
          fileBase="dhanboost-customers"
          columns={[
            { header: "Customer ID", value: (c: CustomerSummary) => c.customerId },
            { header: "Date", value: (c) => (c.createdAt ? formatDateTime(c.createdAt) : "") },
            { header: "Name", value: (c) => c.name ?? "" },
            { header: "PAN", value: (c) => c.pan ?? "" },
            { header: "Mobile", value: (c) => c.mobile ?? "" },
            { header: "Account", value: (c) => c.accountNumber ?? "" },
            { header: "IFSC", value: (c) => c.ifsc ?? "" },
            { header: "Loan", value: (c) => c.latestLoanId ?? "" },
            { header: "Amount (₹)", value: (c) => (c.amountPaise != null ? (c.amountPaise / 100).toFixed(2) : "") },
            { header: "Amount type", value: (c) => (c.amountPaise == null ? "" : c.amountIsRequested ? "requested" : "eligible limit") },
            { header: "Due date", value: (c) => (c.loanDueDate ? formatDate(c.loanDueDate) : "") },
            { header: "DPD", value: (c) => (dpdFor(c.loanDueDate) || "") },
            { header: "Owner", value: (c) => c.ownerName ?? "Unallocated" },
            { header: "Applications", value: (c) => c.applicationCount },
            { header: "Loans", value: (c) => c.loanCount },
            { header: "Latest status", value: (c) => (c.latestStatus ? statusLabel(c.latestStatus as ApplicationStatus) : "") },
            { header: "Stage date", value: (c) => (c.statusChangedAt ? formatDateTime(c.statusChangedAt) : "") },
            { header: "Loan status", value: (c) => c.loanStatus ?? "" },
            { header: "Outstanding (₹)", value: (c) => (c.totalOutstandingPaise / 100).toFixed(2) },
            { header: "Failure", value: (c) => caseFailureLabel(c.failureReason as CaseFailureReason) },
            { header: "Credit score", value: (c) => c.creditScore ?? "" },
            { header: "Credit rating", value: (c) => (c.starRating != null ? c.starRating.toFixed(1) : "") },
            { header: "Credit exec", value: (c) => c.creditDecidedByName ?? "" },
            { header: "Disbursed by", value: (c) => c.disbursedByName ?? "" },
            { header: "Collections exec", value: (c) => c.collectionOfficerName ?? "" },
          ]}
          rows={pageRows}
          getAllRows={() => customersApi.exportAll({ ...baseFilters, seg: seg === "all" ? undefined : seg })}
          allLabel="Download all customers (CSV)"
        />
        <button
          onClick={refreshAll}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {listQ.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="customer:view" fallback={<NoAccessNotice />}>
        <div className="mb-3 flex flex-wrap gap-1.5">
          <button
            type="button"
            className={`cal-preset${seg === "all" ? " on" : ""}`}
            onClick={() => setSeg("all")}
          >
            {SEGMENT_LABEL.all} ({counts.all})
          </button>
          {SEGMENTS.map((s) => (
            <button
              key={s}
              type="button"
              className={`cal-preset${seg === s ? " on" : ""}`}
              onClick={() => setSeg(s)}
            >
              {SEGMENT_LABEL[s]} ({counts[s]})
            </button>
          ))}
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <SearchBar
            initialValue={query}
            onSearch={(t) => {
              setQuery(t);
              setPage(1);
            }}
            placeholder="Name, PAN, mobile, customer or application ID"
            ariaLabel="Search customers"
            inputClassName="w-72"
          />
          <QueueDateFilter period={period} setPeriod={changePeriod} custom={custom} setCustom={changeCustom} />
          {mine && (
            <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">
              My customers
            </span>
          )}
          {showBulkColumn && sel.selected.size > 0 && (
            <BulkActionBar
              count={sel.selected.size}
              onAssign={canBulkAssign ? () => setPendingAssign(selectedIds) : undefined}
              onReject={
                canBulkReject && !mixedRejectModes && bulkRejectMode
                  ? () => setPendingReject({ ids: selectedIds, mode: bulkRejectMode })
                  : undefined
              }
              // A mixed selection keeps Reject in place but disabled, with the reason in a tooltip.
              // It used to make the button vanish, with a small warning line elsewhere in the
              // toolbar — so the operator saw the control disappear rather than learning why.
              // This is the only screen where a selection can span both reject modes.
              rejectDisabledReason={
                canBulkReject && mixedRejectModes ? MIXED_REJECT_MODES_REASON : undefined
              }
            />
          )}
          {/* The order is fixed server-side (CustomerBookQuery: stage date desc, nulls last) and the
              register has no sortable headers, so say so rather than leave the reader guessing. */}
          <span className="ml-auto text-xs text-muted">
            Sorted by stage date <span aria-hidden>↓</span>
            <span className="sr-only">, newest first</span>
          </span>
        </div>

        {!fullView && (
          // Without this a scoped staffer reads a short list as a bug and raises a ticket.
          <p className="mb-3 rounded border border-line bg-grey-50 px-3 py-2 text-xs text-muted">
            Showing only customers assigned to you or that you have recorded a decision on.
          </p>
        )}

        <div className="rounded border border-line bg-white shadow-sm">
          {listQ.isLoading ? (
            <Skeleton variant="table" rows={8} cols={colCount} />
          ) : listQ.error ? (
            <ErrorState error={listQ.error} onRetry={() => void listQ.refetch()} />
          ) : pageRows.length === 0 ? (
            <EmptyState
              title={`No customers${query ? ` for “${query}”` : ""}${seg !== "all" ? ` in ${SEGMENT_LABEL[seg]}` : ""}${
                period !== "ALL" ? " in the selected date range" : ""
              }.`}
            />
          ) : (
            // `staff-register-scroll` bounds the scroller so the sticky `thead` has something to stick
            // to; PaginationBar now sits after it, inside the panel, so it no longer scrolls away.
            // Offset clears the shell header, PageHeader, the segment-chip strip and the search/date row.
            <div className="staff-table-scroll staff-register-scroll" style={{ "--register-offset": "28rem" } as React.CSSProperties}>
            <table className="staff-data-table">
              <caption className="sr-only">Customers, grouped by stage date, newest first</caption>
              <thead>
                {/* Column set deliberately mirrors the live-applications queue (identity, date,
                    contact, PAN, bank, loan, amount, due, credit) so a staffer reads the same row
                    shape on both screens. Account/IFSC/amount/due describe the customer's LATEST
                    application; the roll-up columns (Owner/Loans/Outstanding) are customer-only. */}
                <tr>
                  <th scope="col">S.No.</th>
                  {showBulkColumn && (
                    <th scope="col" className="staff-sticky-identity">
                      <input
                        type="checkbox"
                        checked={actionableIds.length > 0 && sel.selected.size === actionableIds.length}
                        onChange={sel.toggleAll}
                        aria-label="Select all"
                      />
                    </th>
                  )}
                  <th scope="col" className={showBulkColumn ? undefined : "staff-sticky-identity"}>Customer</th>
                  <th scope="col" title="Signup / application start date">Date</th>
                  <th scope="col">Mobile</th>
                  <th scope="col">PAN</th>
                  <th scope="col">Account</th>
                  <th scope="col">IFSC</th>
                  <th scope="col">Loan</th>
                  <th scope="col" className="num">Amount</th>
                  <th scope="col">Due</th>
                  <th scope="col">Owner</th>
                  <th scope="col" className="num">Loans</th>
                  <th scope="col" className="num">Outstanding</th>
                  <th scope="col">Bureau</th>
                  <th scope="col" title="Why this file has no usable credit decision yet">Failure</th>
                  <th scope="col">Latest status</th>
                  <th scope="col" title="When this customer entered their current status">Stage date</th>
                  {/* Who worked the file. "Credit exec" is who DECIDED it, which on a reassigned or
                      Head-decided file is not the same person as Owner. Blank until it happens. */}
                  <th scope="col" title="The credit executive who decided the latest application">Credit exec</th>
                  <th scope="col" title="Who released the money">Disbursed by</th>
                  <th scope="col" title="The assigned collections executive">Collections exec</th>
                  <th scope="col" className="staff-sticky-actions text-right">Open</th>
                </tr>
              </thead>
              <tbody>
                {(() => {
                  let running = (page - 1) * pageSize;
                  return dateGroups.map((group) => {
                    const isCollapsed = collapsedDates.has(group.key);
                    const groupRows = group.rows.map((c) => {
                      running += 1;
                      return { c, sno: running };
                    });
                    return (
                      <React.Fragment key={group.key}>
                        <tr>
                          <td colSpan={colCount} className="bg-grey-50 px-3 py-2">
                            <button
                              type="button"
                              onClick={() => toggleDate(group.key)}
                              className="flex items-center gap-1.5 font-semibold text-ink"
                            >
                              {isCollapsed ? <ChevronRightIcon size={14} /> : <ChevronDown size={14} />}
                              {group.key === "unknown" ? "Date unknown" : formatDate(group.key)} · {group.rows.length} customer
                              {group.rows.length === 1 ? "" : "s"}
                            </button>
                          </td>
                        </tr>
                        {!isCollapsed &&
                          groupRows.map(({ c, sno }) => (
                  <tr key={c.customerId} className="hover:bg-grey-50">
                    <td className="text-muted">{sno}</td>
                    {showBulkColumn && (
                      <td className="staff-sticky-identity">
                        {(() => {
                          const reason = notActionableReason(c);
                          return (
                            <input
                              type="checkbox"
                              checked={c.latestApplicationId != null && sel.selected.has(c.latestApplicationId)}
                              onChange={() => c.latestApplicationId != null && sel.toggle(c.latestApplicationId)}
                              disabled={reason != null}
                              title={reason ?? undefined}
                              aria-label={`Select ${c.name ?? `customer #${c.customerId}`}`}
                            />
                          );
                        })()}
                      </td>
                    )}
                    <td className={`staff-cell${showBulkColumn ? "" : " staff-sticky-identity"}`}>
                      <button
                        onClick={() => setOpenTarget(customerRowTarget(c))}
                        className="flex max-w-full items-center gap-2 text-left"
                        title={c.name ?? undefined}
                      >
                        <span className="grid h-6 w-6 flex-shrink-0 place-items-center rounded-full bg-navy-tint text-navy">
                          <Contact size={13} />
                        </span>
                        <span className="min-w-0">
                          <span className="block truncate font-semibold text-ink hover:underline">{c.name ?? "—"}</span>
                          <span className="block truncate text-xs text-muted">
                            <Link
                              href={`/staff/customers/${c.customerId}`}
                              onClick={(e) => e.stopPropagation()}
                              className="hover:underline"
                            >
                              #{c.customerId}
                            </Link>
                          </span>
                        </span>
                      </button>
                    </td>
                    <td className="whitespace-nowrap text-muted">
                      {c.createdAt ? formatDateTime(c.createdAt) : "—"}
                    </td>
                    <td className="font-mono text-muted">{c.mobile ?? "—"}</td>
                    <td className="font-mono text-ink">{c.pan || "—"}</td>
                    <td className="font-mono text-ink">{c.accountNumber || "—"}</td>
                    <td className="font-mono text-ink">{c.ifsc || "—"}</td>
                    <td className="font-mono text-muted">{c.latestLoanId != null ? `#${c.latestLoanId}` : "—"}</td>
                    <td className="num">
                      <span className="font-semibold text-ink">
                        <AmountCell amountPaise={c.amountPaise} isRequested={c.amountIsRequested === true} />
                      </span>
                    </td>
                    <td className="whitespace-nowrap">
                      <DueCell dueDate={c.loanDueDate} markedPendingAt={c.markedPendingAt} />
                    </td>
                    <td className="staff-cell text-ink">{c.ownerName ?? <span className="text-muted">Unallocated</span>}</td>
                    <td className="num text-ink">{c.loanCount} <span className="text-xs text-muted">/ {c.applicationCount} apps</span></td>
                    <td className="num font-semibold text-ink">{paiseToINR(c.totalOutstandingPaise)}</td>
                    <td>
                      {c.starRating != null || c.creditScore != null ? (
                        <CreditBadge starRating={c.starRating} creditScore={c.creditScore} bureauSource={c.bureauSource} />
                      ) : c.bureauState === "NO_RECORD" ? (
                        <span className="text-xs text-muted">{bureauStateLabel("NO_RECORD", "long")}</span>
                      ) : c.bureauState === "FOUND" ? (
                        // A report CAN come back complete and readable with no usable score (CRIF
                        // sends one outside the 300-900 band). Falling through to "Not fetched" here
                        // would call a report we are holding a report we never pulled.
                        <span className="text-xs text-muted">{reportWithoutScoreLabel("long")}</span>
                      ) : (
                        <span className="text-xs text-muted">{bureauStateLabel("NOT_FETCHED", "short")}</span>
                      )}
                    </td>
                    <td>
                      {isNoFailure(c.failureReason as CaseFailureReason | null | undefined) ? (
                        <span className="text-xs text-muted">—</span>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setFailureCustomer({ id: c.customerId, name: c.name })}
                          title="Why this file has no usable credit decision"
                          className="text-left"
                        >
                          <Badge
                            variant={
                              SEVERITY_BADGE_VARIANT[
                                (c.failureSeverity ?? "INFO") as CaseFailureSeverity
                              ] ?? "default"
                            }
                            size="sm"
                            className="cursor-pointer hover:opacity-80"
                          >
                            {caseFailureLabel(c.failureReason as CaseFailureReason)}
                          </Badge>
                        </button>
                      )}
                    </td>
                    <td>
                      {c.latestStatus ? <StatusBadge kind="application" value={c.latestStatus} /> : "—"}
                    </td>
                    <td className="whitespace-nowrap text-muted">
                      {c.statusChangedAt ? formatDateTime(c.statusChangedAt) : "—"}
                    </td>
                    <td className="staff-cell text-muted" title={c.creditDecidedByName || undefined}>
                      {c.creditDecidedByName || "—"}
                    </td>
                    <td className="staff-cell text-muted" title={c.disbursedByName || undefined}>
                      {c.disbursedByName || "—"}
                    </td>
                    <td className="staff-cell text-muted" title={c.collectionOfficerName || undefined}>
                      {c.collectionOfficerName || "—"}
                    </td>
                    <td className="staff-sticky-actions text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        {canBulkAssign && c.latestApplicationId != null && notActionableReason(c) == null && (
                          <button
                            onClick={() => setPendingAssign([c.latestApplicationId as number])}
                            className="btn btn-sm btn-outline btn-icon"
                            aria-label="Assign"
                            title="Assign"
                          >
                            <UserPlus size={14} />
                          </button>
                        )}
                        {canBulkReject && c.latestApplicationId != null && rowRejectMode(c) != null && (
                          <button
                            onClick={() =>
                              setPendingReject({
                                ids: [c.latestApplicationId as number],
                                mode: rowRejectMode(c) as RejectMode,
                              })
                            }
                            className="btn btn-sm btn-outline btn-icon"
                            aria-label="Reject"
                            title="Reject"
                          >
                            <XIcon size={14} />
                          </button>
                        )}
                        <PermissionGate permission="customer:manage">
                          <button
                            onClick={() => setEditCustomerId(c.customerId)}
                            className="btn btn-sm btn-outline btn-icon"
                            aria-label="Edit customer details"
                            title="Edit customer details"
                          >
                            <Pencil size={14} />
                          </button>
                        </PermissionGate>
                        <button onClick={() => setOpenTarget(customerRowTarget(c))} className="inline-flex items-center gap-1 text-navy hover:underline">
                          Open <ArrowRight size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                          ))}
                      </React.Fragment>
                    );
                  });
                })()}
              </tbody>
            </table>
            </div>
          )}
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>
      </PermissionGate>

      {openTarget?.kind === "application" && (
        // Mounted per open (keyed by id). The customers register opens on the Customer tab.
        <ApplicationDetailDialog
          key={openTarget.applicationId}
          applicationId={openTarget.applicationId}
          initialTab="customer"
          onClose={() => setOpenTarget(null)}
        />
      )}
      <CustomerDetailDialog
        customerId={openTarget?.kind === "customer" ? openTarget.customerId : null}
        onClose={() => setOpenTarget(null)}
      />
      {failureCustomer && (
        <CaseFailureDialog
          customerId={failureCustomer.id}
          customerName={failureCustomer.name}
          onClose={() => setFailureCustomer(null)}
        />
      )}
      {editCustomerId != null && (
        <CustomerEditDialog customerId={editCustomerId} onClose={() => setEditCustomerId(null)} />
      )}
      {pendingReject && (
        <RejectDialog
          ids={pendingReject.ids}
          mode={pendingReject.mode}
          open
          onClose={() => setPendingReject(null)}
          onDone={() => {
            sel.clear();
            invalidateAll();
          }}
        />
      )}
      {pendingAssign && (
        <AssignDialog
          ids={pendingAssign}
          open
          onClose={() => setPendingAssign(null)}
          onDone={() => {
            sel.clear();
            invalidateAll();
          }}
        />
      )}
    </div>
  );
}
