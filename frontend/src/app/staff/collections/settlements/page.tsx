"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Check, X } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, PermissionGate } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { collectionsApi, paiseToINR, type SettlementView, type SettlementStatusName } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { EmptyState, ErrorState, Skeleton, StatusBadge, toast } from "@/components/ui";

const STATUS_LABEL: Record<SettlementStatusName, string> = {
  PROPOSED: "Pending",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

/**
 * Collections · settlements (maker-checker). A Collection Executive proposes
 * (on a case); a Collection Head approves here. SoD is enforced server-side —
 * the approver must differ from the proposer.
 */
export default function CollectionsSettlementsPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["collections-settlements"], queryFn: collectionsApi.listSettlements });

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
  const actionError = approve.error ?? reject.error;
  const busy = approve.isPending || reject.isPending;
  const rows = q.data ?? [];
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  return (
    <div>
      <PageHeader title="Collections · settlements" subtitle="Approve proposed settlements (separation of duties enforced).">
        <ExportMenu
          title="Collections settlements"
          fileBase="dhanboost-settlements"
          columns={[
            { header: "Settlement", value: (s: SettlementView) => s.id },
            { header: "Case", value: (s) => s.collectionCaseId },
            { header: "Amount (₹)", value: (s) => (s.settlementAmountPaise != null ? (s.settlementAmountPaise / 100).toFixed(2) : "") },
            { header: "Status", value: (s) => STATUS_LABEL[s.status] ?? s.status },
            { header: "Proposed by", value: (s) => s.proposedByName ?? (s.proposedBy != null ? `#${s.proposedBy}` : "") },
            { header: "Approved by", value: (s) => s.approvedByName ?? (s.approvedBy != null ? `#${s.approvedBy}` : "") },
            { header: "Rejected by", value: (s) => s.rejectedByName ?? (s.rejectedBy != null ? `#${s.rejectedBy}` : "") },
            { header: "Created", value: (s) => (s.createdAt ? formatDateTime(s.createdAt) : "") },
            { header: "Decided", value: (s) => (s.approvedAt ? formatDateTime(s.approvedAt) : s.rejectedAt ? formatDateTime(s.rejectedAt) : "") },
          ]}
          rows={q.data ?? []}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {actionError && <p className="mb-3 text-sm text-error-700">{errMessage(actionError)}</p>}

      {q.isLoading ? (
        <Skeleton variant="table" rows={8} cols={5} className="rounded border border-line bg-white shadow-sm" />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        // Panel classes sit on this outer wrapper so `PaginationBar` is a sibling AFTER the bounded
        // scroller rather than scrolling away inside it.
        <div className="rounded border border-line bg-white shadow-sm">
          {/* Offset clears the shell header, PageHeader and the occasional action-error line. */}
          <div
            className="staff-table-scroll staff-register-scroll"
            style={{ "--register-offset": "20rem" } as React.CSSProperties}
          >
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th>Settlement</th>
                  <th className="num">Amount</th>
                  <th>Status</th>
                  <th className="text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((s: SettlementView, i: number) => {
                  return (
                    <tr key={s.id}>
                      <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                      <td className="staff-cell">
                        <div className="truncate">
                          <span className="font-mono text-xs text-ink">{s.id.slice(0, 8)}…</span>{" "}
                          <span className="text-xs text-muted">case {s.collectionCaseId.slice(0, 8)}…</span>
                        </div>
                        <div className="truncate text-xs text-muted">
                          {s.createdAt ? formatDateTime(s.createdAt) : ""} · by{" "}
                          {s.proposedByName ?? (s.proposedBy != null ? `#${s.proposedBy}` : "—")}
                        </div>
                      </td>
                      <td className="num font-semibold text-ink">{paiseToINR(s.settlementAmountPaise)}</td>
                      <td>
                        <StatusBadge kind="settlement" value={s.status}>
                          {STATUS_LABEL[s.status] ?? s.status}
                        </StatusBadge>
                      </td>
                      <td className="text-right">
                        {s.status === "APPROVED" ? (
                          <span className="text-xs text-muted">{s.approvedByName ?? (s.approvedBy != null ? `#${s.approvedBy}` : "")} · {s.approvedAt ? formatDateTime(s.approvedAt) : "—"}</span>
                        ) : s.status === "REJECTED" ? (
                          <span className="text-xs text-muted">{s.rejectedByName ?? (s.rejectedBy != null ? `#${s.rejectedBy}` : "")} · {s.rejectedAt ? formatDateTime(s.rejectedAt) : "—"}</span>
                        ) : (
                          // Only the Collection Head (collections:manage) decides; the backend enforces
                          // the role + proposer≠approver SoD too.
                          <PermissionGate
                            permission="collections:manage"
                            fallback={<span className="text-xs italic text-muted">Awaiting Collection Head</span>}
                          >
                            <div className="flex items-center justify-end gap-2">
                              <button
                                onClick={() => approve.mutate(s.id)}
                                disabled={busy}
                                className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
                              >
                                {approve.isPending && approve.variables === s.id ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Approve
                              </button>
                              <button
                                onClick={() => reject.mutate(s.id)}
                                disabled={busy}
                                className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
                              >
                                {reject.isPending && reject.variables === s.id ? <Loader2 size={13} className="animate-spin" /> : <X size={13} />} Reject
                              </button>
                            </div>
                          </PermissionGate>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {rows.length === 0 && <EmptyState title="No settlements proposed." inTable={5} />}
              </tbody>
            </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>
      )}
    </div>
  );
}
