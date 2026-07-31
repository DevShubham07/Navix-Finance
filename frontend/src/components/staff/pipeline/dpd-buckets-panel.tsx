"use client";

/**
 * Collections · DPD buckets — open collection cases grouped by days-past-due.
 *
 * Lifted off the standalone `/staff/collections/buckets` page (removed) so the collections roles
 * work the same single workbench as every other role: `/staff/applications` shows the loans
 * awaiting repayment AND the DPD grid those loans roll up into. The page's "Collectible loans →
 * Open case" panel did not come with it — opening a case is now implicit in assigning an officer
 * ({@link CollectionAssignActions}), which is the only reason a case existed.
 *
 * Each card links into the case workspace (`/staff/collections/{caseId}`), which is unchanged.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, CalendarClock } from "lucide-react";
import { Input, InfoTooltip } from "@/components/ui";
import { ExportMenu } from "@/components/staff/export-menu";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { useCollectionCases } from "@/components/staff/pipeline/collection-actions";
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

export function DpdBucketsPanel() {
  // Same query key as the row-level assign cluster, so assigning an officer refreshes this grid.
  const q = useCollectionCases();

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
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">Collections · DPD buckets</h2>
          {q.isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          <InfoTooltip content="Open collection cases grouped by days past due. A case appears here as soon as a loan is assigned to a collections executive; it drops off once the loan is fully repaid." />
          <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">
            {(q.data ?? []).length}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <ExportMenu
            title="Collections · DPD buckets"
            fileBase="dhanboost-dpd-buckets"
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
        </div>
      </header>

      <div className="p-5">
        <DpdHelper />
        {q.error ? (
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
                              <span className="block text-xs text-muted">{paiseToINR(c.outstandingPaise)} · {c.dpd} DPD · {c.assignedOfficerName ?? "unassigned"}</span>
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
    </section>
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
    <div className="mb-4 flex flex-wrap items-end gap-3 rounded border border-line bg-grey-50 p-3">
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
