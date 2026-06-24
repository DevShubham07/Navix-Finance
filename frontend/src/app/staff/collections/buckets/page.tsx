"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, CalendarClock } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { collectionsApi, type CaseView, type DpdBucket } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

/** Bucket display order + labels. */
const BUCKETS: { key: DpdBucket | string; label: string }[] = [
  { key: "UPCOMING", label: "Upcoming" },
  { key: "T0_T7", label: "1–7 DPD" },
  { key: "T8_T30", label: "8–30 DPD" },
  { key: "T30_T60", label: "31–60 DPD" },
  { key: "T60_T90", label: "61–90 DPD" },
  { key: "T90_PLUS", label: "90+ DPD" },
];

/** Collections · DPD buckets — open cases grouped by days-past-due bucket. */
export default function CollectionsBucketsPage() {
  const q = useQuery({ queryKey: ["collections-cases"], queryFn: collectionsApi.listCases });

  const grouped = React.useMemo(() => {
    const map = new Map<string, CaseView[]>();
    for (const c of q.data ?? []) {
      const k = c.currentBucket || "UPCOMING";
      const arr = map.get(k) ?? [];
      if (arr.length === 0) map.set(k, arr);
      arr.push(c);
    }
    return map;
  }, [q.data]);

  return (
    <div>
      <PageHeader title="Collections · DPD buckets" subtitle="Open collection cases grouped by days-past-due.">
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <DpdHelper />

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {BUCKETS.map(({ key, label }) => {
            const cases = grouped.get(key) ?? [];
            return (
              <div key={key} className="rounded border border-line bg-white shadow-sm">
                <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
                  <span className="font-serif text-base font-semibold text-navy">{label}</span>
                  <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{cases.length}</span>
                </div>
                {cases.length === 0 ? (
                  <p className="px-4 py-5 text-center text-sm text-muted">No cases.</p>
                ) : (
                  <ul className="divide-y divide-line">
                    {cases.map((c) => (
                      <li key={c.id}>
                        <Link href={`/staff/collections/${c.id}`} className="flex items-center gap-3 px-4 py-3 hover:bg-grey-100">
                          <span className="min-w-0 flex-1">
                            <span className="block truncate font-mono text-xs text-ink">{c.id}</span>
                            <span className="block text-xs text-muted">loan {c.loanId.slice(0, 8)}… · {c.createdAt ? formatDate(c.createdAt) : ""}</span>
                          </span>
                          <ArrowRight size={15} className="flex-shrink-0 text-muted" />
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** Quick days-past-due / bucket calculator for a given due date. */
function DpdHelper() {
  const [dueDate, setDueDate] = React.useState("");
  const q = useQuery({
    queryKey: ["dpd", dueDate],
    queryFn: () => collectionsApi.dpd(dueDate),
    enabled: /^\d{4}-\d{2}-\d{2}$/.test(dueDate),
  });
  return (
    <div className="mb-6 flex flex-wrap items-end gap-3 rounded border border-line bg-white p-4 shadow-sm">
      <div className="flex items-center gap-2 text-sm font-semibold text-navy"><CalendarClock size={16} /> DPD calculator</div>
      <Input label="Due date" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} className="!mb-0" />
      {q.data && (
        <div className="text-sm text-ink">
          <strong>{q.data.dpd}</strong> days past due ·{" "}
          <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{q.data.bucket}</span>
          <span className="ml-2 text-xs text-muted">as of {q.data.asOf}</span>
        </div>
      )}
    </div>
  );
}
