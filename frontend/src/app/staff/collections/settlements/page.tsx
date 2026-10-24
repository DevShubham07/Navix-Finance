"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { Loader2, RefreshCw, Check, X, Search } from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { errMessage, PermissionGate } from "@/components/staff/live-pipeline";
import { useStaffMe } from "@/components/staff/pipeline/hooks";
import { pageTitleFor } from "@/components/staff/staff-nav";
import { ExportMenu } from "@/components/staff/export-menu";
import { collectionsApi, paiseToINR, type SettlementView, type SettlementStatusName } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { EmptyState, ErrorState, InfoTooltip, Skeleton, StatusBadge, toast } from "@/components/ui";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  SETTLEMENT_SEGMENTS,
  SETTLEMENT_SEGMENT_LABEL,
  isOwnSettlementProposal,
  settlementRecoveryPct,
  settlementWaiverPaise,
  settlementSegmentCounts,
  type SettlementSegment,
} from "@/lib/collections/settlement-segments";

const STATUS_LABEL: Record<SettlementStatusName, string> = {
  PROPOSED: "Pending",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

/** Sortable + filterable columns. `createdAt` heads the Proposed cell because that cell's readable
 *  content IS the proposal's date and proposer — the two uuids in it are identifiers, not something
 *  anyone narrows a register by. */
const SORT_KEYS = ["borrowerName", "outstandingPaise", "settlementAmountPaise", "createdAt", "status"] as const;

const COLUMN_LABEL: Record<string, string> = {
  borrowerName: "Customer",
  outstandingPaise: "Outstanding",
  settlementAmountPaise: "Settlement",
  createdAt: "Proposed",
  status: "Status",
};

const COLS = 8;

/** One searchable string per row: name, mobile, customer/loan ids, settlement/case ids. */
function haystack(s: SettlementView): string {
  return [s.borrowerName, s.mobile, s.customerId, s.loanId, s.id, s.collectionCaseId]
    .filter((v) => v != null && v !== "")
    .join(" ")
    .toLowerCase();
}

const by = (name: string | null, id: number | null) => name ?? (id != null ? `#${id}` : "—");

/** Active-chip tone, matching the status badge's meaning (the base `.cal-preset.on` is navy). */
const SEGMENT_TONE_CLASS: Partial<Record<SettlementSegment, string>> = {
  APPROVED: " cal-preset--success",
  REJECTED: " cal-preset--error",
};

/**
 * Collections · settlements (maker-checker). A Collection Executive proposes
 * (on a case); a Collection Head approves here. SoD is enforced server-side —
 * the approver must differ from the proposer.
 *
 * Each row carries the borrower and loan behind the case, so a decision is made against who is
 * being settled, today's dues and what the settlement writes off — and Approve / Reject both go
 * through a confirmation that restates exactly that.
 */
export default function CollectionsSettlementsPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["collections-settlements"], queryFn: collectionsApi.listSettlements });
  // Pre-flight SoD only: the server's proposer≠approver check stays the authority. Until the
  // session loads `myId` is undefined, which never matches — the buttons show and the server decides.
  const me = useStaffMe().data;
  const myId = me?.id;
  const role = me?.role;

  const approve = useMutation({
    mutationFn: (id: string) => collectionsApi.approveSettlement(id),
    onSuccess: () => {
      toast.success("Settlement approved");
      return qc.invalidateQueries({ queryKey: ["collections-settlements"] });
    },
  });
  const reject = useMutation({
    mutationFn: (id: string) => collectionsApi.rejectSettlement(id),
    onSuccess: () => {
      toast.success("Settlement rejected");
      return qc.invalidateQueries({ queryKey: ["collections-settlements"] });
    },
  });
  // The decision waiting on its confirmation dialog.
  const [pending, setPending] = React.useState<{ action: "approve" | "reject"; s: SettlementView } | null>(null);
  // A new decision clears BOTH mutations' last error first. Each mutation only resets its own state
  // when it runs again, so an old SOD_VIOLATION from Approve otherwise stayed above the table after
  // a later Reject succeeded.
  const decide = (action: "approve" | "reject", id: string) => {
    approve.reset();
    reject.reset();
    const done = { onSettled: () => setPending(null) };
    if (action === "approve") approve.mutate(id, done);
    else reject.mutate(id, done);
  };
  const actionError = approve.error ?? reject.error;
  const busy = approve.isPending || reject.isPending;

  const rows = React.useMemo(() => q.data ?? [], [q.data]);
  const [segment, setSegment] = React.useState<SettlementSegment>("ALL");
  const [search, setSearch] = React.useState("");
  const counts = React.useMemo(() => settlementSegmentCounts(rows), [rows]);
  const totals = React.useMemo(() => {
    let pendingPaise = 0;
    let pendingWaiverPaise = 0;
    let approvedPaise = 0;
    for (const s of rows) {
      if (s.status === "PROPOSED") {
        pendingPaise += s.settlementAmountPaise ?? 0;
        pendingWaiverPaise += settlementWaiverPaise(s) ?? 0;
      } else if (s.status === "APPROVED") {
        approvedPaise += s.settlementAmountPaise ?? 0;
      }
    }
    return { pendingPaise, pendingWaiverPaise, approvedPaise };
  }, [rows]);

  const rq = useRegisterQuery({
    registerId: "settlements",
    staffId: myId,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<SettlementView>>>(
    () => [
      { key: "borrowerName", kind: "text", value: (s) => s.borrowerName ?? "" },
      { key: "outstandingPaise", kind: "num", value: (s) => paiseToINR(s.outstandingPaise), num: (s) => (s.outstandingPaise != null ? s.outstandingPaise / 100 : null) },
      // In rupees: the bound is typed next to a rupee column.
      { key: "settlementAmountPaise", kind: "num", value: (s) => paiseToINR(s.settlementAmountPaise), num: (s) => (s.settlementAmountPaise != null ? s.settlementAmountPaise / 100 : null) },
      { key: "createdAt", kind: "date", value: (s) => (s.createdAt ? formatDateTime(s.createdAt) : "—"), date: (s) => istDay(s.createdAt) },
      // The checklist reads "Pending", the filter travels as PROPOSED.
      { key: "status", value: (s) => STATUS_LABEL[s.status] ?? s.status, raw: (s) => s.status },
    ],
    [],
  );

  // The segment chip and the search box narrow first and the chip counts stay over the unfiltered
  // rows, so the chips do not collapse as you use them.
  const needle = search.trim().toLowerCase();
  const inActiveSegment = React.useCallback(
    (s: SettlementView) => (segment === "ALL" || s.status === segment) && (!needle || haystack(s).includes(needle)),
    [segment, needle],
  );
  const cr = useClientRegister(rows, filterColumns, rq, inActiveSegment);
  const { pageRows, total, pageCount } = cr;
  const visible = cr.rows;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  const dialogRow = pending?.s;
  const dialogWaiver = dialogRow ? settlementWaiverPaise(dialogRow) : null;
  const dialogPct = dialogRow ? settlementRecoveryPct(dialogRow) : null;

  return (
    <div>
      <PageHeader
        title={pageTitleFor("/staff/collections/settlements", role, "Collections · settlements")}
        subtitle={["Review proposed settlements against the customer's dues (separation of duties enforced).", describeRegister(th)]
          .filter(Boolean)
          .join(" · ")}
      >
        <ExportMenu
          title="Collections settlements"
          fileBase="dhanboost-settlements"
          columns={[
            { header: "Settlement", value: (s: SettlementView) => s.id },
            { header: "Case", value: (s) => s.collectionCaseId },
            { header: "Customer", value: (s) => s.borrowerName ?? "" },
            { header: "Customer ID", value: (s) => (s.customerId != null ? String(s.customerId) : "") },
            { header: "Mobile", value: (s) => s.mobile ?? "" },
            { header: "Loan ID", value: (s) => (s.loanId != null ? String(s.loanId) : "") },
            { header: "Due date", value: (s) => (s.dueDate ? formatDate(s.dueDate) : "") },
            { header: "DPD", value: (s) => String(s.dpd ?? 0) },
            { header: "Outstanding (₹)", value: (s) => (s.outstandingPaise != null ? (s.outstandingPaise / 100).toFixed(2) : "") },
            { header: "Amount (₹)", value: (s) => (s.settlementAmountPaise != null ? (s.settlementAmountPaise / 100).toFixed(2) : "") },
            { header: "Waiver (₹)", value: (s) => { const w = settlementWaiverPaise(s); return w != null ? (w / 100).toFixed(2) : ""; } },
            { header: "Status", value: (s) => STATUS_LABEL[s.status] ?? s.status },
            { header: "Proposed by", value: (s) => (s.proposedByName ?? (s.proposedBy != null ? `#${s.proposedBy}` : "")) },
            { header: "Approved by", value: (s) => (s.approvedByName ?? (s.approvedBy != null ? `#${s.approvedBy}` : "")) },
            { header: "Rejected by", value: (s) => (s.rejectedByName ?? (s.rejectedBy != null ? `#${s.rejectedBy}` : "")) },
            { header: "Created", value: (s) => (s.createdAt ? formatDateTime(s.createdAt) : "") },
            { header: "Decided", value: (s) => (s.approvedAt ? formatDateTime(s.approvedAt) : s.rejectedAt ? formatDateTime(s.rejectedAt) : "") },
          ]}
          // The segment-filtered set the table actually renders, not the raw query result: narrowing
          // to Rejected and hitting Export used to hand back every settlement of every status.
          rows={visible}
          scope="all"
        />
        <button
          onClick={() => q.refetch()}
          disabled={q.isFetching}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink disabled:opacity-50"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {!q.isLoading && !q.error && (
        <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard
            label="Awaiting decision"
            value={counts.PROPOSED}
            hint={`${paiseToINR(totals.pendingPaise)} proposed`}
            accent="gold"
            info="Settlements proposed by a collections officer that a Collection Head has not yet approved or rejected."
          />
          <StatCard
            label="Waiver if approved"
            value={paiseToINR(totals.pendingWaiverPaise)}
            hint="Today's dues minus the proposed amounts"
            accent="error"
            info="What approving every pending settlement would let borrowers off, against their outstanding today."
          />
          <StatCard label="Approved" value={counts.APPROVED} hint={`${paiseToINR(totals.approvedPaise)} agreed`} accent="success" />
          <StatCard label="Total settlements" value={counts.ALL} hint={`${counts.REJECTED} rejected`} />
        </div>
      )}

      {actionError && <p className="mb-3 text-sm text-error-700">{errMessage(actionError)}</p>}

      {!q.isLoading && !q.error && (
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by status">
            {SETTLEMENT_SEGMENTS.map((seg) => (
              <button
                key={seg}
                type="button"
                aria-pressed={segment === seg}
                className={`cal-preset${segment === seg ? ` on${SEGMENT_TONE_CLASS[seg] ?? ""}` : ""}`}
                onClick={() => {
                  setSegment(seg);
                  // "What matches has changed" — back to page 1 in one update, filters untouched.
                  if (seg !== segment) rq.apply({});
                }}
              >
                {SETTLEMENT_SEGMENT_LABEL[seg]} ({counts[seg]})
              </button>
            ))}
          </div>
          <label className="relative block w-full sm:w-72">
            <span className="sr-only">Search settlements</span>
            <Search size={14} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted" />
            <input
              type="search"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                rq.apply({});
              }}
              placeholder="Name, mobile, customer or loan ID"
              className="field w-full pl-8 text-sm"
            />
          </label>
        </div>
      )}

      {q.isLoading ? (
        <Skeleton variant="table" rows={8} cols={COLS} className="rounded border border-line bg-white shadow-sm" />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        // Panel classes sit on this outer wrapper so `PaginationBar` is a sibling AFTER the bounded
        // scroller rather than scrolling away inside it.
        <div className="rounded border border-line bg-white shadow-sm">
          {/* Offset clears the shell header, PageHeader, the stat tiles, the status chips and the
              occasional action-error line. */}
          <div
            className="staff-table-scroll staff-register-scroll"
            style={{ "--register-offset": "31rem" } as React.CSSProperties}
          >
            <table className="staff-data-table">
              <caption className="sr-only">Collection settlements</caption>
              <thead>
                <tr>
                  <th scope="col">S.No.</th>
                  <Th th={th} col="borrowerName" />
                  <th scope="col">Loan</th>
                  <Th th={th} col="outstandingPaise" className="num" />
                  <Th th={th} col="settlementAmountPaise" className="num" />
                  <Th th={th} col="createdAt" />
                  <Th th={th} col="status" />
                  <th scope="col" className="text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((s: SettlementView, i: number) => {
                  const waiver = settlementWaiverPaise(s);
                  const pct = settlementRecoveryPct(s);
                  return (
                    <tr key={s.id} className={s.status === "PROPOSED" ? "bg-gold-soft/20" : undefined}>
                      <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                      <td className="staff-cell">
                        {s.customerId != null ? (
                          <Link href={`/staff/customers/${s.customerId}`} className="font-medium text-navy hover:underline">
                            {s.borrowerName ?? `Customer #${s.customerId}`}
                          </Link>
                        ) : (
                          <span className="font-medium text-ink">{s.borrowerName ?? "—"}</span>
                        )}
                        <div className="truncate text-xs text-muted">
                          {[s.mobile, s.customerId != null ? `Cust #${s.customerId}` : null].filter(Boolean).join(" · ") || "—"}
                        </div>
                      </td>
                      <td className="staff-cell">
                        {s.loanId != null ? (
                          <Link href={`/staff/collections/${s.loanId}`} className="font-mono text-xs text-navy hover:underline">
                            Loan #{s.loanId}
                          </Link>
                        ) : (
                          <span className="text-xs text-muted">—</span>
                        )}
                        <div className="truncate text-xs text-muted">
                          {s.dueDate ? `Due ${formatDate(s.dueDate)}` : ""}
                          {s.dpd > 0 ? (
                            <span className="ml-1 font-semibold text-error-700">· {s.dpd} DPD</span>
                          ) : s.loanStatus ? (
                            <span className="ml-1">· {s.loanStatus.replace(/_/g, " ").toLowerCase()}</span>
                          ) : null}
                        </div>
                      </td>
                      <td className="num text-ink">{paiseToINR(s.outstandingPaise)}</td>
                      <td className="num">
                        <div className="font-semibold text-ink">{paiseToINR(s.settlementAmountPaise)}</div>
                        {pct != null && (
                          <div className="text-xs text-muted">
                            {pct}% of dues{waiver ? ` · ${paiseToINR(waiver)} waived` : ""}
                          </div>
                        )}
                      </td>
                      <td className="staff-cell">
                        <div className="flex items-center gap-1">
                          <div className="min-w-0 truncate text-xs text-ink">
                            {s.createdAt ? formatDateTime(s.createdAt) : "—"}
                          </div>
                          <InfoTooltip
                            label="Full settlement and case ids"
                            size={12}
                            content={
                              <>
                                <span className="block text-muted">Settlement</span>
                                <span className="block break-all font-mono">{s.id}</span>
                                <span className="mt-1.5 block text-muted">Case</span>
                                <span className="block break-all font-mono">{s.collectionCaseId}</span>
                              </>
                            }
                          />
                        </div>
                        <div className="truncate text-xs text-muted">by {by(s.proposedByName, s.proposedBy)}</div>
                      </td>
                      <td>
                        <StatusBadge kind="settlement" value={s.status}>
                          {STATUS_LABEL[s.status] ?? s.status}
                        </StatusBadge>
                      </td>
                      <td className="text-right">
                        {s.status === "APPROVED" ? (
                          <span className="text-xs text-muted">{by(s.approvedByName, s.approvedBy)} · {s.approvedAt ? formatDateTime(s.approvedAt) : "—"}</span>
                        ) : s.status === "REJECTED" ? (
                          <span className="text-xs text-muted">{by(s.rejectedByName, s.rejectedBy)} · {s.rejectedAt ? formatDateTime(s.rejectedAt) : "—"}</span>
                        ) : (
                          // Only the Collection Head (collections:manage) decides; the backend enforces
                          // the role + proposer≠approver SoD too.
                          <PermissionGate
                            permission="collections:manage"
                            fallback={<span className="text-xs italic text-muted">Awaiting Collection Head</span>}
                          >
                            {isOwnSettlementProposal(s, myId) ? (
                              // The server would refuse either button with SOD_VIOLATION — say so up
                              // front instead of offering two actions that cannot succeed.
                              <span className="text-xs italic text-muted">
                                You proposed this — another Collection Head must decide
                              </span>
                            ) : (
                              <div className="flex items-center justify-end gap-2">
                                <button
                                  onClick={() => setPending({ action: "approve", s })}
                                  disabled={busy}
                                  className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
                                >
                                  {approve.isPending && approve.variables === s.id ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Approve
                                </button>
                                <button
                                  onClick={() => setPending({ action: "reject", s })}
                                  disabled={busy}
                                  className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
                                >
                                  {reject.isPending && reject.variables === s.id ? <Loader2 size={13} className="animate-spin" /> : <X size={13} />} Reject
                                </button>
                              </div>
                            )}
                          </PermissionGate>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {rows.length === 0 ? (
                  <EmptyState title="No settlements proposed." inTable={COLS} />
                ) : visible.length === 0 ? (
                  <EmptyState
                    title={
                      rq.activeCount > 0 || needle
                        ? "No settlements match the search or column filters."
                        : `No ${SETTLEMENT_SEGMENT_LABEL[segment].toLowerCase()} settlements.`
                    }
                    inTable={COLS}
                  />
                ) : null}
              </tbody>
            </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={rq.setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={rq.setSize}
          />
        </div>
      )}

      <ConfirmDialog
        open={pending != null}
        onClose={() => setPending(null)}
        onConfirm={() => pending && decide(pending.action, pending.s.id)}
        busy={busy}
        title={pending?.action === "approve" ? "Approve this settlement?" : "Reject this settlement?"}
        confirmLabel={pending?.action === "approve" ? "Approve settlement" : "Reject settlement"}
        body={
          dialogRow && (
            <div className="space-y-3">
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded border border-line bg-ivory p-3">
                <dt className="text-muted">Customer</dt>
                <dd className="text-ink">
                  {dialogRow.borrowerName ?? "—"}
                  {dialogRow.mobile ? <span className="text-muted"> · {dialogRow.mobile}</span> : null}
                </dd>
                <dt className="text-muted">Loan</dt>
                <dd className="text-ink">
                  {dialogRow.loanId != null ? `#${dialogRow.loanId}` : "—"}
                  {dialogRow.dpd > 0 ? <span className="text-error-700"> · {dialogRow.dpd} days past due</span> : null}
                </dd>
                <dt className="text-muted">Outstanding today</dt>
                <dd className="font-mono text-ink">{paiseToINR(dialogRow.outstandingPaise)}</dd>
                <dt className="text-muted">Settlement amount</dt>
                <dd className="font-mono font-semibold text-ink">
                  {paiseToINR(dialogRow.settlementAmountPaise)}
                  {dialogPct != null ? <span className="font-sans font-normal text-muted"> ({dialogPct}% of dues)</span> : null}
                </dd>
                <dt className="text-muted">Waived</dt>
                <dd className="font-mono text-error-700">{dialogWaiver != null ? paiseToINR(dialogWaiver) : "—"}</dd>
                <dt className="text-muted">Proposed by</dt>
                <dd className="text-ink">
                  {by(dialogRow.proposedByName, dialogRow.proposedBy)}
                  {dialogRow.createdAt ? <span className="text-muted"> · {formatDateTime(dialogRow.createdAt)}</span> : null}
                </dd>
              </dl>
              <p>
                {pending?.action === "approve"
                  ? "Approving caps what this borrower owes at the settlement amount; the balance above it is written off once the settlement payment is booked. This cannot be undone."
                  : "Rejecting closes this proposal for good — the borrower still owes the full outstanding, and the officer must propose afresh."}
              </p>
            </div>
          )
        }
      />
    </div>
  );
}
