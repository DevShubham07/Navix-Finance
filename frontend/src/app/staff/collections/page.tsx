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
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, Eye } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { ExportMenu } from "@/components/staff/export-menu";
import { useQueueSelection } from "@/components/staff/pipeline/bulk-actions";
import { useStaffMe } from "@/components/staff/pipeline/hooks";
import { hasPermission } from "@/lib/auth/rbac";
import { BulkAssignOfficerDialog, InlineOfficerSelect } from "@/components/staff/collections-assign";
import { AdminLogPaymentButton } from "@/components/staff/admin-log-payment";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import {
  collectionsApi,
  customersApi,
  paiseToINR,
  type CollectionHandoverRow,
  type CustomerSummary,
  type WorklistRow,
} from "@/lib/api/applications";
import { collectionHandoverColumns } from "@/lib/export/collection-handover-columns";
import { COLLECTION_BUCKETS, isDpdBucket } from "@/lib/collection-buckets";
import { worklistDueCue, worklistDueLabel, type WorklistDueCue } from "@/lib/collections/worklist-due";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { formatDate, formatDateTime } from "@/lib/utils";

/** Flattened row: the sort primitive compares top-level keys, and `loan` is a nested object. */
interface Row {
  loanId: number;
  /** The application behind this specific loan — what the quick-view dialog opens on. Null only if
   *  the loan snapshot is missing, in which case the quick-view button is not offered. */
  applicationId: number | null;
  /** Joins into the customer directory (`customersById`) for export-only fields — mobile, bank,
   *  credit score, signup/history — the worklist snapshot itself doesn't carry. */
  customerId: number | null;
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
    applicationId: w.loan?.applicationId ?? null,
    customerId: w.loan?.customerId ?? null,
    dpd: w.dpd,
    preDue: w.preDue,
    caseId: w.caseId,
    borrowerName: w.loan?.borrowerName ?? null,
    mobile: w.loan?.mobile ?? null,
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

/** The export-only customer enrichment changes when a profile is edited, not between menu opens. */
const EXPORT_ENRICHMENT_STALE_MS = 5 * 60_000;

/** DPD-cell tone per due state: red once late, navy on the day, muted while still ahead. */
const DUE_CUE_CLASS: Record<WorklistDueCue["kind"], string> = {
  overdue: "num font-semibold text-error-700",
  "due-today": "num whitespace-nowrap font-semibold text-navy",
  "due-in": "num whitespace-nowrap text-muted",
  none: "num",
};

/** The DPD cell: the server's count once late, otherwise how far off the due date is (IST). */
function DpdCell({ row, now }: { row: Row; now: Date }) {
  const cue = worklistDueCue(row, now);
  return <td className={DUE_CUE_CLASS[cue.kind]}>{worklistDueLabel(cue)}</td>;
}

export default function CollectionsBucketPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const search = useSearchParams();
  const raw = search.get("bucket");
  const bucket = isDpdBucket(raw) ? raw : "UPCOMING";
  const meta = COLLECTION_BUCKETS.find((item) => item.bucket === bucket)!;

  // Deep link from the global-search palette (`?q=…`).
  const [query, setQuery] = React.useState(search.get("q") ?? "");

  // Quick-view only. "Open" still navigates to the collections case workspace — that page is where
  // interactions, settlement and the case itself get created, and none of that lives in this dialog.
  const [previewApplicationId, setPreviewApplicationId] = React.useState<number | null>(null);
  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = rangeFor(period, custom);

  const q = useQuery({
    queryKey: ["collections-worklist"],
    queryFn: collectionsApi.worklist,
    refetchInterval: 8000,
  });

  // The customers behind the worklist — sorted so the set is a stable query key across the 8s poll
  // and the bucket switcher (the worklist rows re-order; the id set does not).
  const worklistCustomerIds = React.useMemo(() => {
    const ids = new Set<number>();
    for (const w of q.data ?? []) {
      if (w.loan?.customerId != null) ids.add(w.loan.customerId);
    }
    return [...ids].sort((a, b) => a - b);
  }, [q.data]);

  // Export-only enrichment (mobile, bank, credit score, signup/history) the worklist snapshot
  // doesn't carry, joined in by customerId below. `enabled: false` on purpose: it is read ONLY by
  // the <ExportMenu> column definitions, and ExportMenu renders nothing for a non-ADMIN — so
  // fetching it on mount made every collections visitor pay for data almost none of them ever see.
  // It now loads when the menu is opened (`onOpen`), for exactly the customers on the worklist.
  //
  // `onOpen` prefetches through the client rather than calling `refetch()`: `refetch()` ignores
  // `staleTime`, so every open re-downloaded the same customers. `prefetchQuery` only fetches when
  // this id set has no data yet or it is older than the staleTime.
  const exportCustomersQuery = {
    queryKey: ["collections-export-customers", worklistCustomerIds],
    queryFn: () => customersApi.byIdsAll(worklistCustomerIds),
    staleTime: EXPORT_ENRICHMENT_STALE_MS,
  };
  const customersQ = useQuery({ ...exportCustomersQuery, enabled: false });
  const customersById = React.useMemo(() => {
    const m = new Map<number, CustomerSummary>();
    for (const c of customersQ.data ?? []) m.set(c.customerId, c);
    return m;
  }, [customersQ.data]);
  const customerFor = (id: number | null) => (id != null ? customersById.get(id) : undefined);

  // The agency-handover half of the export: full identity, both addresses, references and every
  // verification result, keyed by the loan's own application. Same on-demand rule as above.
  const worklistApplicationIds = React.useMemo(() => {
    const ids = new Set<number>();
    for (const w of q.data ?? []) {
      if (w.loan?.applicationId != null) ids.add(w.loan.applicationId);
    }
    return [...ids].sort((a, b) => a - b);
  }, [q.data]);
  const exportHandoverQuery = {
    queryKey: ["collections-export-handover", worklistApplicationIds],
    queryFn: () => customersApi.collectionHandoverAll(worklistApplicationIds),
    staleTime: EXPORT_ENRICHMENT_STALE_MS,
  };
  const handoverQ = useQuery({ ...exportHandoverQuery, enabled: false });
  const handover = React.useMemo(() => {
    const m = new Map<number, CollectionHandoverRow>();
    for (const h of handoverQ.data ?? []) m.set(h.applicationId, h);
    return collectionHandoverColumns<Row>((id) => (id != null ? m.get(id) : undefined));
  }, [handoverQ.data]);

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
    const needle = query.trim().toLowerCase();
    const inBucket = (q.data ?? []).filter((w) => w.bucket === bucket).map(toRow);
    return inBucket.filter((r) => {
      if (needle) {
        const hay = [
          r.customerId != null ? String(r.customerId) : null,
          r.borrowerName,
          r.mobile,
          r.pan,
          r.employer,
          r.officerName,
          String(r.loanId),
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      // The date window filters on the due date — for a collections register "which of these fall
      // due today" is the question, and a case-opened date is meaningless on rows with no case.
      if (range.from && (!r.dueDate || r.dueDate < range.from)) return false;
      if (range.to && (!r.dueDate || r.dueDate > range.to)) return false;
      return true;
    });
  }, [q.data, bucket, query, range.from, range.to]);

  // One reading of "now" per render for the DPD cell's days-to-due; the 8s poll re-renders, so it
  // never drifts more than a poll behind. The helper measures it in IST.
  const now = new Date();

  // Most overdue first: the top of a collections list should be the loan that has run longest.
  const { sorted, sortKey, dir, toggle } = useTableSort<Row>(filtered, "dpd", "desc");
  const { page, setPage, pageSize, setPageSize, pageCount, pageRows, total } = usePagination(sorted);

  // Bulk assign of a collections executive. `collections:manage` is COLLECTION_HEAD + ADMIN — the
  // same pair the per-case assign endpoint already permits, so the button and the server agree.
  const role = useStaffMe().data?.role;
  const canBulkAssign = role != null && hasPermission(role, "collections:manage");
  const [bulkOpen, setBulkOpen] = React.useState(false);
  const pageLoanIds = React.useMemo(() => pageRows.map((r) => r.loanId), [pageRows]);
  const selection = useQueueSelection(pageLoanIds);
  // `useQueueSelection` only resets when the row COUNT changes, which paging usually preserves — so
  // intersect with the rows actually on screen. A bulk assign must never touch a loan the operator
  // paged or filtered away from.
  const selectedLoanIds = React.useMemo(
    () => pageLoanIds.filter((id) => selection.selected.has(id)),
    [pageLoanIds, selection.selected],
  );
  const allPageSelected = pageLoanIds.length > 0 && selectedLoanIds.length === pageLoanIds.length;
  // Same reason as above, for the header checkbox: `selection.toggleAll` compares set SIZE, so a
  // same-size selection carried over from another page would make "select all" read as "clear".
  const toggleAllOnPage = () => {
    if (allPageSelected) selection.clear();
    else for (const id of pageLoanIds) if (!selection.selected.has(id)) selection.toggle(id);
  };

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
          onOpen={() => {
            void qc.prefetchQuery(exportCustomersQuery);
            void qc.prefetchQuery(exportHandoverQuery);
          }}
          disabled={customersQ.isFetching || handoverQ.isFetching}
          // Ordered for the reader outside the company: who, their PAN, where they live — then
          // how to reach them, what they owe, and every check result we hold.
          columns={[
            { header: "Borrower", value: (r) => dash(r.borrowerName) },
            { header: "PAN", value: (r) => dash(customerFor(r.customerId)?.pan ?? r.pan) },
            ...handover.addresses,
            { header: "Mobile", value: (r) => dash(customerFor(r.customerId)?.mobile ?? r.mobile) },
            ...handover.contact,
            ...handover.identity,
            ...handover.references,
            { header: "Loan", value: (r) => String(r.loanId) },
            { header: "Principal (₹)", value: (r) => (r.principalPaise != null ? (r.principalPaise / 100).toFixed(2) : "") },
            { header: "Outstanding (₹)", value: (r) => (r.outstandingPaise != null ? (r.outstandingPaise / 100).toFixed(2) : "") },
            { header: "Disbursed on", value: (r) => (r.disbursedOn ? formatDate(r.disbursedOn) : "—") },
            { header: "Due date", value: (r) => (r.dueDate ? formatDate(r.dueDate) : "—") },
            { header: "DPD", value: (r) => String(r.dpd) },
            { header: "Pre-due", value: (r) => (r.preDue ? "Yes" : "No") },
            { header: "Loan status", value: (r) => dash(r.loanStatus) },
            ...handover.sanction,
            ...handover.bank,
            { header: "Employer", value: (r) => dash(r.employer) },
            { header: "Monthly salary (₹)", value: (r) => (r.salaryPaise != null ? (r.salaryPaise / 100).toFixed(2) : "") },
            ...handover.employment,
            ...handover.company,
            ...handover.pan,
            ...handover.digilocker,
            ...handover.selfieAndEsign,
            ...handover.credit,
            { header: "Customer ID", value: (r) => (r.customerId != null ? String(r.customerId) : "—") },
            { header: "Credit exec", value: (r) => dash(r.creditDecidedByName) },
            { header: "Disbursed by", value: (r) => dash(r.disbursedByName) },
            { header: "Collections exec", value: (r) => dash(r.officerName) },
            { header: "Case ID", value: (r) => dash(r.caseId) },
            { header: "Case opened", value: (r) => (r.caseOpenedAt ? formatDateTime(r.caseOpenedAt) : "—") },
            {
              header: "Signup date",
              value: (r) => {
                const c = customerFor(r.customerId);
                return c?.createdAt ? formatDateTime(c.createdAt) : "—";
              },
            },
            {
              header: "Applications",
              value: (r) => {
                const c = customerFor(r.customerId);
                return c?.applicationCount != null ? String(c.applicationCount) : "—";
              },
            },
            {
              header: "Loans",
              value: (r) => {
                const c = customerFor(r.customerId);
                return c?.loanCount != null ? String(c.loanCount) : "—";
              },
            },
            ...handover.consent,
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
        <SearchBar
          initialValue={query}
          onSearch={(t) => {
            setQuery(t);
            setPage(1);
          }}
          placeholder="Customer ID, name, mobile, PAN, employer, officer or loan #"
          ariaLabel="Search"
          inputClassName="max-w-xs"
        />
        <QueueDateFilter period={period} setPeriod={setPeriod} custom={custom} setCustom={setCustom} />
        {/* Selection is page-scoped on purpose (see `selectedLoanIds`), so the bar says so. */}
        {canBulkAssign && selectedLoanIds.length > 0 && (
          <div className="flex items-center gap-2 pb-1">
            <span className="text-xs font-semibold text-navy">
              {selectedLoanIds.length} selected on this page
            </span>
            <button type="button" onClick={() => setBulkOpen(true)} className="btn btn-sm btn-navy">
              Assign selected
            </button>
          </div>
        )}
      </div>

      {/* The panel look lives on this outer wrapper so `PaginationBar` can sit AFTER the scroller,
          not inside it — bounding the scroller (`staff-register-scroll`) would otherwise scroll the
          pagination away with the rows. */}
      <div className="rounded border border-line bg-white shadow-sm">
        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.isLoading ? (
          <Skeleton variant="table" rows={8} cols={canBulkAssign ? 17 : 16} />
        ) : total === 0 ? (
          <EmptyState
            title={`No loans in ${meta.label}${query.trim() ? ` for “${query.trim()}”` : ""}${
              period !== "ALL" ? " in the selected date range" : ""
            }.`}
          />
        ) : (
          // Offset clears the shell header, PageHeader, the bucket-card row and the search/date/bulk
          // toolbar above the register.
          <div
            className="staff-table-scroll staff-register-scroll"
            style={{ "--register-offset": "30rem" } as React.CSSProperties}
          >
            <table className="staff-data-table">
              <caption className="sr-only">Collections worklist · {meta.label}</caption>
              <thead>
                <tr>
                  <th scope="col">S.No.</th>
                  {canBulkAssign && (
                    // Takes over the sticky-left slot while it renders — two cells pinned at `left: 0`
                    // would sit on top of each other.
                    <th scope="col" className="staff-sticky-identity">
                      <input
                        type="checkbox"
                        checked={allPageSelected}
                        onChange={toggleAllOnPage}
                        aria-label="Select all loans on this page"
                      />
                    </th>
                  )}
                  <SortableTh label="Customer ID" sortKey="customerId" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh
                    className={canBulkAssign ? undefined : "staff-sticky-identity"}
                    label="Borrower"
                    sortKey="borrowerName"
                    active={sortKey}
                    dir={dir}
                    onToggle={toggle}
                  />
                  <th scope="col">Mobile</th>
                  <th scope="col">PAN</th>
                  <SortableTh label="Loan" sortKey="loanId" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh
                    className="num"
                    label="Principal"
                    sortKey="principalPaise"
                    active={sortKey}
                    dir={dir}
                    onToggle={toggle}
                  />
                  <SortableTh
                    className="num"
                    label="Outstanding"
                    sortKey="outstandingPaise"
                    active={sortKey}
                    dir={dir}
                    onToggle={toggle}
                  />
                  <SortableTh label="Due" sortKey="dueDate" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh className="num" label="DPD" sortKey="dpd" active={sortKey} dir={dir} onToggle={toggle} />
                  <th scope="col">Employer</th>
                  <SortableTh
                    className="num"
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
                  <th scope="col" className="staff-sticky-actions text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((r, i) => (
                  <tr key={r.loanId}>
                    {/* Row-level overdue cue: an inset bar on the leading cell. A box-shadow, not a
                        fill, so it stays visible over the zebra stripe and the hover background. */}
                    <td className={r.dpd > 0 ? "shadow-[inset_3px_0_0_0_theme(colors.error.600)]" : undefined}>
                      {(page - 1) * pageSize + i + 1}
                    </td>
                    {canBulkAssign && (
                      <td className="staff-sticky-identity">
                        <input
                          type="checkbox"
                          checked={selection.selected.has(r.loanId)}
                          onChange={() => selection.toggle(r.loanId)}
                          aria-label={`Select loan #${r.loanId}`}
                        />
                      </td>
                    )}
                    <td className="font-mono">{r.customerId ?? "—"}</td>
                    <td className={canBulkAssign ? undefined : "staff-sticky-identity"}>
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
                    <td className="font-mono text-xs">{dash(r.mobile)}</td>
                    <td className="font-mono text-xs">{dash(r.pan)}</td>
                    <td className="font-mono">#{r.loanId}</td>
                    <td className="num font-mono">{paiseToINR(r.principalPaise)}</td>
                    <td className="num font-mono font-semibold">{paiseToINR(r.outstandingPaise)}</td>
                    <td className={r.dpd > 0 ? "font-semibold text-error-700" : undefined}>
                      {r.dueDate ? formatDate(r.dueDate) : "—"}
                    </td>
                    <DpdCell row={r} now={now} />
                    <td>{dash(r.employer)}</td>
                    <td className="num font-mono">{r.salaryPaise != null ? paiseToINR(r.salaryPaise) : "—"}</td>
                    <td>{dash(r.creditDecidedByName)}</td>
                    <td>{dash(r.disbursedByName)}</td>
                    <td>
                      <InlineOfficerSelect
                        loanId={r.loanId}
                        officerId={r.officerId}
                        officerName={r.officerName}
                      />
                    </td>
                    <td className="staff-sticky-actions">
                      <div className="flex items-center justify-end gap-1.5">
                        <AdminLogPaymentButton loanId={r.loanId} loanStatus={r.loanStatus} compact />
                        {r.applicationId != null && (
                          <button
                            onClick={() => setPreviewApplicationId(r.applicationId)}
                            className="btn btn-sm btn-outline btn-icon"
                            aria-label="Quick view application"
                            title="Quick view — see the application without leaving the worklist"
                          >
                            <Eye size={14} />
                          </button>
                        )}
                        <Link href={`/staff/collections/${r.loanId}`} className="btn btn-sm btn-outline">
                          Open <ArrowRight size={14} />
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
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

      <BulkAssignOfficerDialog
        loanIds={selectedLoanIds}
        open={bulkOpen}
        onClose={() => setBulkOpen(false)}
        onDone={selection.clear}
      />

      <ApplicationDetailDialog
        applicationId={previewApplicationId}
        onClose={() => setPreviewApplicationId(null)}
      />
    </div>
  );
}
