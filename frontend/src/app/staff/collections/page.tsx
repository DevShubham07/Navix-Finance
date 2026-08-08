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

  return (
    <div>
      <PageHeader title={`Collections · ${meta.label}`} subtitle="Open collection cases in this live, computed-on-read DPD bucket.">
        <button onClick={() => q.refetch()} className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100">
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
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
      {q.error ? <p className="text-sm text-error-700">{errMessage(q.error)}</p> : q.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : rows.length === 0 ? (
        <div className="rounded border border-line bg-white px-5 py-10 text-center text-sm text-muted">No cases in {meta.label}.</div>
      ) : (
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
    </div>
  );
}
