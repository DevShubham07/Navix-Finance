"use client";

/**
 * The DPD bucket register.
 *
 * Two things were wrong with what this replaced. It was a bare `<ul>` of three-field rows while every
 * other staff register (customers, live applications, loans) is a real sortable, searchable,
 * exportable table — and, worse, it listed **collection cases**, which nothing creates
 * automatically. A borrower ten days past due whom nobody had clicked "Open case" on appeared in no
 * bucket at all, so the page showed the bookkeeping rather than the debt.
 *
 * It now renders the loan worklist: every live loan already past due, plus those falling due within
 * the next week so an officer can take an update *before* salary day. The collection case is an
 * internal row created behind the first real action (assign, log an interaction, propose a
 * settlement) — there is no "open a case" step for a human to remember any more.
 */

import * as React from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { Input } from "@/components/ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { ExportMenu } from "@/components/staff/export-menu";
import { WorklistAssignActions } from "@/components/staff/collections-assign";
import { AdminLogPaymentButton } from "@/components/staff/admin-log-payment";
import { collectionsApi, paiseToINR, type WorklistRow } from "@/lib/api/applications";
import { COLLECTION_BUCKETS, isDpdBucket } from "@/lib/collection-buckets";
import { formatDate } from "@/lib/utils";

/** Flattened row: the sort primitive compares top-level keys, and `loan` is a nested object. */
interface Row {
  loanId: number;
  dpd: number;
  preDue: boolean;
  caseId: string | null;
  borrowerName: string | null;
  mobile: string | null;
  pan: string | null;
  employer: string | null;
  salaryPaise: number | null;
  principalPaise: number | null;
  outstandingPaise: number | null;
  dueDate: string | null;
  disbursedOn: string | null;
  loanStatus: string | null;
  officerName: string | null;
  officerId: number | null;
  creditDecidedByName: string | null;
  disbursedByName: string | null;
  caseOpenedAt: string | null;
}

function toRow(w: WorklistRow): Row {
  return {
    loanId: w.loanId,
    dpd: w.dpd,
    preDue: w.preDue,
    caseId: w.caseId,
    borrowerName: w.loan?.borrowerName ?? null,
    // The worklist snapshot carries no mobile — the borrower's number lives on the customer record,
    // one click away on the case workspace. Left out rather than faked.
    mobile: null,
    pan: w.loan?.panMasked ?? null,
    employer: w.loan?.employer ?? null,
    salaryPaise: w.loan?.monthlySalaryPaise ?? null,
    principalPaise: w.loan?.principalPaise ?? null,
    outstandingPaise: w.loan?.outstandingPaise ?? null,
    dueDate: w.loan?.dueDate ?? null,
    disbursedOn: w.loan?.disbursedOn ?? null,
    loanStatus: w.loan?.status ?? null,
    officerName: w.assignedOfficerName,
    officerId: w.assignedOfficerId,
    creditDecidedByName: w.creditDecidedByName,
    disbursedByName: w.disbursedByName,
    caseOpenedAt: w.caseOpenedAt,
  };
}

const dash = (v: string | null | undefined) => (v && v.trim() ? v : "—");

export default function CollectionsBucketPage() {
  const router = useRouter();
  const search = useSearchParams();
  const raw = search.get("bucket");
  const bucket = isDpdBucket(raw) ? raw : "UPCOMING";
  const meta = COLLECTION_BUCKETS.find((item) => item.bucket === bucket)!;

  const [query, setQuery] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim().toLowerCase()), 300);
    return () => clearTimeout(t);
  }, [query]);

  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = rangeFor(period, custom);

  const q = useQuery({
    queryKey: ["collections-worklist"],
    queryFn: collectionsApi.worklist,
    refetchInterval: 8000,
  });

  // Counts across ALL buckets, from the unfiltered set — the header cards are a map of the whole
  // book, so they must not move when the operator narrows one bucket.
  const counts = React.useMemo(() => {
    const c: Record<string, { n: number; outstandingPaise: number }> = {};
    for (const { bucket: b } of COLLECTION_BUCKETS) c[b] = { n: 0, outstandingPaise: 0 };
    for (const w of q.data ?? []) {
      const slot = c[w.bucket];
      if (slot) {
        slot.n += 1;
        slot.outstandingPaise += w.loan?.outstandingPaise ?? 0;
      }
    }
    return c;
  }, [q.data]);

  const filtered = React.useMemo(() => {
    const inBucket = (q.data ?? []).filter((w) => w.bucket === bucket).map(toRow);
    return inBucket.filter((r) => {
      if (debounced) {
        const hay = [r.borrowerName, r.pan, r.employer, r.officerName, String(r.loanId)]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();
        if (!hay.includes(debounced)) return false;
      }
      // The date window filters on the due date — for a collections register "which of these fall
      // due today" is the question, and a case-opened date is meaningless on rows with no case.
      if (range.from && (!r.dueDate || r.dueDate < range.from)) return false;
      if (range.to && (!r.dueDate || r.dueDate > range.to)) return false;
      return true;
    });
  }, [q.data, bucket, debounced, range.from, range.to]);

  // Most overdue first: the top of a collections list should be the loan that has run longest.
  const { sorted, sortKey, dir, toggle } = useTableSort<Row>(filtered, "dpd", "desc");
  const { page, setPage, pageSize, setPageSize, pageCount, pageRows, total } = usePagination(sorted);

  return (
    <div>
      <PageHeader
        title={`Collections · ${meta.label}`}
        subtitle="Every live loan in this bucket — past due, or falling due within the week. DPD is computed on read."
      >
        <ExportMenu<Row>
          title={`Collections — ${meta.label}`}
          fileBase={`collections-${bucket.toLowerCase()}`}
          rows={sorted}
          columns={[
            { header: "Loan", value: (r) => String(r.loanId) },
            { header: "Borrower", value: (r) => dash(r.borrowerName) },
            { header: "PAN", value: (r) => dash(r.pan) },
            { header: "Employer", value: (r) => dash(r.employer) },
            { header: "Principal", value: (r) => paiseToINR(r.principalPaise) },
            { header: "Outstanding", value: (r) => paiseToINR(r.outstandingPaise) },
            { header: "Due date", value: (r) => (r.dueDate ? formatDate(r.dueDate) : "—") },
            { header: "DPD", value: (r) => String(r.dpd) },
            { header: "Credit exec", value: (r) => dash(r.creditDecidedByName) },
            { header: "Disbursed by", value: (r) => dash(r.disbursedByName) },
            { header: "Collections exec", value: (r) => dash(r.officerName) },
          ]}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* Bucket cards double as the switcher — the old page hid a <select> behind `lg:hidden`, so on
          desktop there was no way to move between buckets from the page itself. */}
      <div className="mb-4 grid grid-cols-2 gap-2 md:grid-cols-3 lg:grid-cols-6">
        {COLLECTION_BUCKETS.map((item) => {
          const active = item.bucket === bucket;
          const c = counts[item.bucket] ?? { n: 0, outstandingPaise: 0 };
          return (
            <button
              key={item.bucket}
              onClick={() => router.push(`/staff/collections?bucket=${item.bucket}`)}
              className={`rounded border p-3 text-left transition ${
                active ? "border-navy bg-navy-tint" : "border-line bg-white hover:bg-grey-50"
              }`}
            >
              <p className="text-xs text-muted">{item.label}</p>
              <p className="font-mono text-lg font-semibold text-navy">{c.n}</p>
              <p className="text-xs text-muted">{paiseToINR(c.outstandingPaise)}</p>
            </button>
          );
        })}
      </div>

      <div className="mb-3 flex flex-wrap items-end gap-3">
        <Input
          label="Search"
          placeholder="Name, PAN, employer, officer or loan #"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="!mb-0 max-w-xs"
        />
        <QueueDateFilter period={period} setPeriod={setPeriod} custom={custom} setCustom={setCustom} />
      </div>

      <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
        {q.error ? (
          <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
        ) : q.isLoading ? (
          <div className="h-32 animate-pulse bg-white" />
        ) : total === 0 ? (
          <p className="px-5 py-8 text-center text-sm text-muted">
            No loans in {meta.label}
            {debounced ? ` for “${query.trim()}”` : ""}
            {period !== "ALL" ? " in the selected date range" : ""}.
          </p>
        ) : (
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>S.No.</th>
                <SortableTh
                  className="staff-sticky-identity"
                  label="Borrower"
                  sortKey="borrowerName"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <th>PAN</th>
                <SortableTh label="Loan" sortKey="loanId" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh
                  label="Principal"
                  sortKey="principalPaise"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <SortableTh
                  label="Outstanding"
                  sortKey="outstandingPaise"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <SortableTh label="Due" sortKey="dueDate" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="DPD" sortKey="dpd" active={sortKey} dir={dir} onToggle={toggle} />
                <th>Employer</th>
                <SortableTh
                  label="Salary"
                  sortKey="salaryPaise"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <SortableTh
                  label="Credit exec"
                  sortKey="creditDecidedByName"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <SortableTh
                  label="Disbursed by"
                  sortKey="disbursedByName"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <SortableTh
                  label="Collections exec"
                  sortKey="officerName"
                  active={sortKey}
                  dir={dir}
                  onToggle={toggle}
                />
                <th className="staff-sticky-actions text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageRows.map((r, i) => (
                <tr key={r.loanId}>
                  <td>{(page - 1) * pageSize + i + 1}</td>
                  <td className="staff-sticky-identity">
                    <span className="font-semibold text-ink">{dash(r.borrowerName)}</span>
                    {r.preDue && (
                      <span
                        className="ml-2 rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy"
                        title="Not yet due — a courtesy follow-up, not a delinquency"
                      >
                        Not yet due
                      </span>
                    )}
                  </td>
                  <td className="font-mono text-xs">{dash(r.pan)}</td>
                  <td className="font-mono">#{r.loanId}</td>
                  <td className="font-mono">{paiseToINR(r.principalPaise)}</td>
                  <td className="font-mono font-semibold">{paiseToINR(r.outstandingPaise)}</td>
                  <td className={r.dpd > 0 ? "font-semibold text-error-700" : undefined}>
                    {r.dueDate ? formatDate(r.dueDate) : "—"}
                  </td>
                  <td className={r.dpd > 0 ? "font-semibold text-error-700" : undefined}>{r.dpd}</td>
                  <td>{dash(r.employer)}</td>
                  <td className="font-mono">{r.salaryPaise != null ? paiseToINR(r.salaryPaise) : "—"}</td>
                  <td>{dash(r.creditDecidedByName)}</td>
                  <td>{dash(r.disbursedByName)}</td>
                  <td>{dash(r.officerName)}</td>
                  <td className="staff-sticky-actions">
                    <div className="flex items-center justify-end gap-1.5">
                      <AdminLogPaymentButton loanId={r.loanId} loanStatus={r.loanStatus} compact />
                      <WorklistAssignActions
                        loanId={r.loanId}
                        assignedOfficerName={r.officerName}
                        compact
                      />
                      <Link href={`/staff/collections/${r.loanId}`} className="btn btn-sm btn-outline">
                        Open <ArrowRight size={14} />
                      </Link>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {total > 0 && (
          <PaginationBar
            page={page}
            setPage={setPage}
            pageSize={pageSize}
            setPageSize={setPageSize}
            pageCount={pageCount}
            total={total}
          />
        )}
      </div>
    </div>
  );
}
