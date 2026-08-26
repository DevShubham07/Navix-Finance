"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { Select } from "@/components/ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { collectionsApi, paiseToINR } from "@/lib/api/applications";
import { COLLECTION_BUCKETS, isDpdBucket } from "@/lib/collection-buckets";
import { formatDate } from "@/lib/utils";

export default function CollectionsBucketPage() {
  const router = useRouter();
  const search = useSearchParams();
  const raw = search.get("bucket");
  const bucket = isDpdBucket(raw) ? raw : "UPCOMING";
  const meta = COLLECTION_BUCKETS.find((item) => item.bucket === bucket)!;
  const q = useQuery({ queryKey: ["collection-cases"], queryFn: collectionsApi.listCases, refetchInterval: 8000 });
  const rows = (q.data ?? []).filter((item) => item.bucket === bucket);

  // UPCOMING is the one bucket an open case can essentially never reach: a case is only ever opened
  // against a loan that is already due, so the bucket named for "not due yet" rendered empty forever.
  // These rows are the loans themselves — watched, not worked, so they carry no case and no actions.
  const isUpcoming = bucket === "UPCOMING";
  const upcomingQ = useQuery({
    queryKey: ["collection-upcoming"],
    queryFn: collectionsApi.listUpcoming,
    enabled: isUpcoming,
    // Deliberately slower than the 8s case poll: a loan that is not due yet cannot change bucket
    // within a minute, and this is much the widest list on the page.
    refetchInterval: 60_000,
  });
  const upcoming = isUpcoming ? upcomingQ.data ?? [] : [];

  const fetching = q.isFetching || (isUpcoming && upcomingQ.isFetching);
  const loading = q.isLoading || (isUpcoming && upcomingQ.isLoading);
  const error = q.error ?? (isUpcoming ? upcomingQ.error : null);
  const refresh = () => {
    q.refetch();
    if (isUpcoming) upcomingQ.refetch();
  };

  return (
    <div>
      <PageHeader
        title={`Collections · ${meta.label}`}
        subtitle={isUpcoming
          ? "Live loans whose repayment date is still ahead, soonest first. Nothing to collect yet — this is a watchlist, not a worklist."
          : "Open collection cases in this live, computed-on-read DPD bucket."}
      >
        <button onClick={refresh} className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100">
          {fetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>
      <Select
        label="DPD bucket"
        value={bucket}
        onChange={(event) => router.push(`/staff/collections?bucket=${event.target.value}`)}
        className="mb-4 max-w-xs lg:hidden"
      >
        {COLLECTION_BUCKETS.map((item) => <option key={item.bucket} value={item.bucket}>{item.label}</option>)}
      </Select>
      {error ? <p className="text-sm text-error-700">{errMessage(error)}</p> : loading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : rows.length === 0 && upcoming.length === 0 ? (
        <div className="rounded border border-line bg-white px-5 py-10 text-center text-sm text-muted">
          {isUpcoming ? "No loans are due ahead." : `No cases in ${meta.label}.`}
        </div>
      ) : (
        <div className="space-y-4">
          {rows.length > 0 && (
            <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
              <ul className="divide-y divide-line">
                {rows.map((item) => (
                  <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                    <div>
                      <p className="font-semibold text-ink">{item.borrowerName ?? `Loan #${item.loanId}`}</p>
                      <p className="text-xs text-muted">{item.dpd} DPD · due {item.dueDate ? formatDate(item.dueDate) : "—"} · {paiseToINR(item.outstandingPaise)}</p>
                    </div>
                    <Link href={`/staff/collections/${item.id}`} className="btn btn-sm btn-outline">Open case</Link>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {upcoming.length > 0 && (
            <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
              {rows.length > 0 && (
                <p className="border-b border-line bg-grey-100 px-5 py-2 text-xs font-semibold uppercase tracking-wide text-muted">
                  Not yet due · no case open
                </p>
              )}
              <ul className="divide-y divide-line">
                {upcoming.map((item) => (
                  <li key={item.loanId} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                    <div>
                      {item.customerId != null ? (
                        <Link href={`/staff/customers/${item.customerId}`} className="font-semibold text-ink hover:underline">
                          {item.borrowerName ?? `Loan #${item.loanId}`}
                        </Link>
                      ) : (
                        <p className="font-semibold text-ink">{item.borrowerName ?? `Loan #${item.loanId}`}</p>
                      )}
                      <p className="text-xs text-muted">
                        due {formatDate(item.dueDate)} · in {item.daysToDue} {item.daysToDue === 1 ? "day" : "days"} · {paiseToINR(item.outstandingPaise)}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
