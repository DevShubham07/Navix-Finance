"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, CalendarClock, Banknote } from "lucide-react";
import { Input, InfoTooltip } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { collectionsApi, paiseToINR, type CaseView, type DpdBucket } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/** Bucket display order + labels + what each days-past-due band means. */
const BUCKETS: { key: DpdBucket | string; label: string; info: string }[] = [
  { key: "UPCOMING", label: "Upcoming", info: "Not yet overdue (due today or later). Pre-due reminders only." },
  { key: "T0_T7", label: "1–7 DPD", info: "1–7 days past due. Early, low-intensity follow-up." },
  { key: "T8_T30", label: "8–30 DPD", info: "8–30 days past due. Active collections; the 2%/day late penalty is accruing." },
  { key: "T30_T60", label: "31–60 DPD", info: "31–60 days past due. Escalated follow-up; consider a settlement or revised plan (Collection Head approves)." },
  { key: "T60_T90", label: "61–90 DPD", info: "61–90 days past due. High risk of default; intensive recovery." },
  { key: "T90_PLUS", label: "90+ DPD", info: "Over 90 days past due — default territory; write-off / legal review." },
];

/** Collections · DPD buckets — open cases grouped by days-past-due bucket. */
export default function CollectionsBucketsPage() {
  const q = useQuery({ queryKey: ["collections-cases"], queryFn: collectionsApi.listCases });

  const grouped = React.useMemo(() => {
    const map = new Map<string, CaseView[]>();
    for (const c of q.data ?? []) {
      const k = c.bucket || "UPCOMING";
      const arr = map.get(k);
      if (arr) arr.push(c);
      else map.set(k, [c]);
    }
    return map;
  }, [q.data]);

  return (
    <div>
      <PageHeader title="Collections · DPD buckets" subtitle="Open collection cases grouped by days-past-due.">
        <ExportMenu
          title="Collections · DPD buckets"
          fileBase="navix-dpd-buckets"
          columns={[
            { header: "Bucket", value: (c: CaseView) => c.bucket },
            { header: "DPD", value: (c) => c.dpd },
            { header: "Loan", value: (c) => c.loanId },
            { header: "Borrower", value: (c) => c.borrowerName ?? "" },
            { header: "Status", value: (c) => c.loanStatus ?? "" },
            { header: "Outstanding (₹)", value: (c) => (c.outstandingPaise != null ? (c.outstandingPaise / 100).toFixed(2) : "") },
            { header: "Due date", value: (c) => c.dueDate ?? "" },
            { header: "Officer", value: (c) => c.assignedOfficerName ?? "" },
            { header: "Opened", value: (c) => (c.createdAt ? formatDateTime(c.createdAt) : "") },
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

      <DpdHelper />
      <CollectiblePanel />

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {BUCKETS.map(({ key, label, info }) => {
            const cases = grouped.get(key) ?? [];
            return (
              <div key={key} className="rounded border border-line bg-white shadow-sm">
                <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
                  <div className="flex items-center gap-1.5">
                    <span className="font-serif text-base font-semibold text-navy">{label}</span>
                    <InfoTooltip content={info} />
                  </div>
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
                            <span className="block truncate text-xs font-semibold text-ink">Loan #{c.loanId} · {c.borrowerName ?? "—"}</span>
                            <span className="block text-xs text-muted">{paiseToINR(c.outstandingPaise)} · {c.dpd} DPD · {c.createdAt ? formatDateTime(c.createdAt) : ""}</span>
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

/** Loans due for collections (ACTIVE/OVERDUE) with a one-click "open case". */
function CollectiblePanel() {
  const qc = useQueryClient();
  const router = useRouter();
  const q = useQuery({ queryKey: ["collectible-loans"], queryFn: () => collectionsApi.listCollectibleLoans() });
  const open = useMutation({
    mutationFn: (loanId: number) => collectionsApi.openCase(loanId),
    onSuccess: (detail) => {
      qc.invalidateQueries({ queryKey: ["collections-cases"] });
      qc.invalidateQueries({ queryKey: ["collectible-loans"] });
      router.push(`/staff/collections/${detail.id}`);
    },
  });
  const loans = q.data ?? [];
  return (
    <div className="mb-6 rounded border border-line bg-white p-4 shadow-sm">
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy">
        <Banknote size={16} /> Collectible loans
        <InfoTooltip content="ACTIVE/OVERDUE loans due on or before today that don't yet have a collection case. Click 'Open case' to start collections — the loan moves into a DPD bucket above." />
      </div>
      {open.error && <p className="mb-2 text-sm text-error-700">{errMessage(open.error)}</p>}
      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : loans.length === 0 ? (
        <p className="text-sm text-muted">No loans currently due for collections.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {loans.map((l) => (
            <li key={l.loanId} className="flex items-center justify-between gap-3 py-2">
              <span className="min-w-0">
                <span className="text-ink">Loan #{l.loanId} · {l.borrowerName ?? "—"}</span>
                <span className="block text-xs text-muted">{paiseToINR(l.outstandingPaise)} · due {l.dueDate ?? "—"} · {l.status}</span>
              </span>
              <button
                onClick={() => open.mutate(l.loanId)}
                disabled={open.isPending}
                className="btn btn-sm btn-navy disabled:opacity-50"
              >
                {open.isPending && open.variables === l.loanId ? <Loader2 size={13} className="animate-spin" /> : null} Open case
              </button>
            </li>
          ))}
        </ul>
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
