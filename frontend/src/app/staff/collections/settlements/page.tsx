"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Check } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, PermissionGate } from "@/components/staff/live-pipeline";
import { collectionsApi, paiseToINR, type SettlementView } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

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
    onSuccess: () => qc.invalidateQueries({ queryKey: ["collections-settlements"] }),
  });

  return (
    <div>
      <PageHeader title="Collections · settlements" subtitle="Approve proposed settlements (separation of duties enforced).">
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {approve.error && <p className="mb-3 text-sm text-error-700">{errMessage(approve.error)}</p>}

      {q.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-2.5">Settlement</th>
                <th className="px-4 py-2.5">Amount</th>
                <th className="px-4 py-2.5">Status</th>
                <th className="px-4 py-2.5 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {(q.data ?? []).map((s: SettlementView) => {
                const approved = !!s.approvedBy;
                return (
                  <tr key={s.id}>
                    <td className="px-4 py-3">
                      <div className="font-mono text-xs text-ink">{s.id.slice(0, 8)}…</div>
                      <div className="text-xs text-muted">case {s.collectionCaseId.slice(0, 8)}… · {s.createdAt ? formatDateTime(s.createdAt) : ""}</div>
                      <div className="text-xs text-muted">by {s.proposedByName ?? (s.proposedBy != null ? `#${s.proposedBy}` : "—")}</div>
                    </td>
                    <td className="px-4 py-3 font-semibold text-ink">{paiseToINR(s.settlementAmountPaise)}</td>
                    <td className="px-4 py-3">
                      {approved ? (
                        <span className="rounded-full bg-success-100 px-2.5 py-0.5 text-xs font-semibold text-success-800">Approved</span>
                      ) : (
                        <span className="rounded-full bg-warning-50 px-2.5 py-0.5 text-xs font-semibold text-warning-800">Pending</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {approved ? (
                        <span className="text-xs text-muted">{s.approvedByName ?? (s.approvedBy != null ? `#${s.approvedBy}` : "")} · {s.approvedAt ? formatDateTime(s.approvedAt) : "—"}</span>
                      ) : (
                        // Only the Collection Head (collections:manage) approves; the backend enforces
                        // the role + proposer≠approver SoD too.
                        <PermissionGate
                          permission="collections:manage"
                          fallback={<span className="text-xs italic text-muted">Awaiting Collection Head</span>}
                        >
                          <button
                            onClick={() => approve.mutate(s.id)}
                            disabled={approve.isPending}
                            className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
                          >
                            {approve.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Approve
                          </button>
                        </PermissionGate>
                      )}
                    </td>
                  </tr>
                );
              })}
              {(q.data ?? []).length === 0 && (
                <tr><td colSpan={4} className="px-4 py-6 text-center text-muted">No settlements proposed.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
