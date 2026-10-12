"use client";

/**
 * DPD buckets (all) — read-only company-wide view of the collections worklist: one card per bucket
 * (count + outstanding), click-through to that bucket's working page, and one combined table.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/live-pipeline";
import { useStaffMe, useCan } from "@/components/staff/pipeline/hooks";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { TONE_SOLID, gradientCss } from "@/components/staff/dashboard/colors";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { collectionsApi, paiseToINR, type DpdBucket } from "@/lib/api/applications";
import { BUCKET_TONE, COLLECTION_BUCKETS } from "@/lib/collection-buckets";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
const BUCKET_LABEL = Object.fromEntries(COLLECTION_BUCKETS.map((b) => [b.bucket, b.label])) as Record<DpdBucket, string>;

interface Row {
  loanId: number;
  borrower: string;
  dpd: number;
  bucket: DpdBucket;
  bucketLabel: string;
  outstandingPaise: number;
  officer: string;
}

export default function DpdOverviewPage() {
  const myRole = useStaffMe().data?.role;
  const can = useCan();
  const q = useQuery({ queryKey: ["collections-worklist"], queryFn: collectionsApi.worklist, refetchInterval: 30_000 });

  const cards = React.useMemo(() => {
    const m = new Map<DpdBucket, { n: number; paise: number }>(COLLECTION_BUCKETS.map((b) => [b.bucket, { n: 0, paise: 0 }]));
    for (const w of q.data ?? []) {
      const slot = m.get(w.bucket);
      if (slot) {
        slot.n += 1;
        slot.paise += w.loan?.outstandingPaise ?? 0;
      }
    }
    return m;
  }, [q.data]);

  const rows = React.useMemo<Row[]>(
    () =>
      (q.data ?? []).map((w) => ({
        loanId: w.loanId,
        borrower: w.loan?.borrowerName ?? "—",
        dpd: w.dpd,
        bucket: w.bucket,
        bucketLabel: BUCKET_LABEL[w.bucket] ?? w.bucket,
        outstandingPaise: w.loan?.outstandingPaise ?? 0,
        officer: w.assignedOfficerName ?? "Unassigned",
      })),
    [q.data],
  );
  const { sorted, sortKey, dir, toggle } = useTableSort<Row>(rows, "dpd", "desc");
  const { page, setPage, pageSize, setPageSize, pageCount, pageRows, total } = usePagination(sorted);
  // The customer pop-up, opened from a borrower's name (resolved loan → customer → application).
  const [openLoanId, setOpenLoanId] = React.useState<number | null>(null);

  if (myRole && !can("collections:overview")) {
    return <NoAccessNotice message="Collection Head or Admin access only." />;
  }

  const th = { active: sortKey, dir, onToggle: toggle };

  return (
    <div>
      <PageHeader
        title="DPD buckets (all)"
        subtitle="Every live loan across all buckets, read-only. Open a bucket to work its cases."
      />

      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-6">
        {COLLECTION_BUCKETS.map(({ bucket, label }) => {
          const c = cards.get(bucket) ?? { n: 0, paise: 0 };
          return (
            <Link
              key={bucket}
              href={`/staff/collections?bucket=${bucket}`}
              title={`${label}: ${c.n.toLocaleString("en-IN")} loans · ${paiseToINR(c.paise)} outstanding`}
              className="block rounded-lg p-4 text-white shadow-sm transition-transform hover:-translate-y-0.5 hover:shadow-md"
              style={{ background: gradientCss(BUCKET_TONE[bucket]) }}
            >
              <div className="text-xs font-semibold opacity-90">{label}</div>
              <div className="mt-1 font-serif text-3xl font-bold">{q.isLoading ? "…" : c.n}</div>
              <div className="mt-1 text-xs opacity-90">{q.isLoading ? "" : paiseToINR(c.paise)}</div>
            </Link>
          );
        })}
      </div>

      <div className="rounded border border-line bg-white shadow-sm">
        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.isLoading ? (
          <Skeleton variant="table" rows={8} cols={6} />
        ) : total === 0 ? (
          <EmptyState title="No live loans in any DPD bucket." />
        ) : (
          <div className="staff-register-scroll">
            <table className="staff-data-table">
              <caption className="sr-only">All DPD buckets</caption>
              <thead>
                <tr>
                  <SortableTh label="Borrower" sortKey="borrower" {...th} />
                  <SortableTh label="Loan #" sortKey="loanId" {...th} />
                  <SortableTh label="DPD" sortKey="dpd" {...th} />
                  <SortableTh label="Bucket" sortKey="bucketLabel" {...th} />
                  <SortableTh label="Outstanding" sortKey="outstandingPaise" {...th} />
                  <SortableTh label="Assigned officer" sortKey="officer" {...th} />
                </tr>
              </thead>
              <tbody>
                {pageRows.map((r) => (
                  <tr key={r.loanId}>
                    <td>
                      <button
                        type="button"
                        onClick={() => setOpenLoanId(r.loanId)}
                        className="font-medium text-navy hover:underline"
                        title={`Open ${r.borrower}`}
                      >
                        {r.borrower}
                      </button>
                    </td>
                    <td>{r.loanId}</td>
                    <td>{r.dpd}</td>
                    <td>
                      <span
                        className="inline-block rounded-full px-2 py-0.5 text-xs font-semibold text-white"
                        style={{ background: TONE_SOLID[BUCKET_TONE[r.bucket]] }}
                      >
                        {r.bucketLabel}
                      </span>
                    </td>
                    <td>{paiseToINR(r.outstandingPaise)}</td>
                    <td>{r.officer}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {total > 0 && (
          <PaginationBar page={page} setPage={setPage} pageSize={pageSize} setPageSize={setPageSize} pageCount={pageCount} total={total} />
        )}
      </div>

      <ApplicationDetailDialog applicationId={null} loanId={openLoanId} initialTab="collections" onClose={() => setOpenLoanId(null)} />
    </div>
  );
}
