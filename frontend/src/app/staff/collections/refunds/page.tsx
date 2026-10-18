"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { collectionsApi, paiseToINR, type DebitRecord } from "@/lib/api/applications";
import { EmptyState, ErrorState, InfoTooltip, Skeleton } from "@/components/ui";
import { formatDateTime } from "@/lib/utils";

/**
 * Refunds owed: NACH debits that collected money from a borrower who had already repaid (the loan
 * settled while the debit was in flight, so the credit was refused and the money is ours to return).
 *
 * This page exists because an overpayment used to be a boolean column and one WARN line — real money
 * owed to a real person that nobody could find. It is deliberately a flat read of every such row ever
 * recorded, with no date window and no "resolved" state: marking a refund paid is a finance workflow we
 * have not built, so hiding rows would lose the only record there is.
 */
export default function RefundsOwedPage() {
  const q = useQuery({ queryKey: ["debit-overpayments"], queryFn: () => collectionsApi.overpayments() });
  const rows: DebitRecord[] = q.data ?? [];
  const total = rows.reduce((sum, r) => sum + r.amountPaise, 0);

  return (
    <div className="space-y-5">
      <PageHeader
        title="Refunds owed"
        subtitle="NACH debits that took money a borrower no longer owed"
      >
        <button type="button" className="btn btn--ghost" onClick={() => void q.refetch()} disabled={q.isFetching}>
          <RefreshCw size={15} className={q.isFetching ? "animate-spin" : undefined} /> Refresh
        </button>
      </PageHeader>

      {q.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyState title="No refunds owed." hint="Every successful debit landed on a loan that still had a balance." />
      ) : (
        <div className="card overflow-x-auto">
          <div className="flex items-center gap-2 border-b border-line px-4 py-3 text-sm">
            <span className="text-muted">Total owed</span>
            <span className="font-mono font-semibold text-navy">{paiseToINR(total)}</span>
            <InfoTooltip content="Money collected by NACH from borrowers whose loans were already settled. Each row needs returning to the borrower; this register is the record that it is owed." />
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-muted">
                <th className="px-4 py-2 font-medium">Loan</th>
                <th className="px-4 py-2 font-medium">Amount</th>
                <th className="px-4 py-2 font-medium">UTR</th>
                <th className="px-4 py-2 font-medium">Settled on</th>
                <th className="px-4 py-2 font-medium">Presented</th>
                <th className="px-4 py-2 font-medium">Trigger</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.debitId} className="border-b border-line/60 last:border-0">
                  <td className="px-4 py-2 font-mono">#{r.loanId}</td>
                  <td className="px-4 py-2 font-mono font-semibold text-navy">{paiseToINR(r.amountPaise)}</td>
                  <td className="px-4 py-2 font-mono">{r.utr ?? "—"}</td>
                  <td className="px-4 py-2">{r.settlementDate ?? "—"}</td>
                  <td className="px-4 py-2">{r.createdAt ? formatDateTime(r.createdAt) : "—"}</td>
                  <td className="px-4 py-2">{r.trigger ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
