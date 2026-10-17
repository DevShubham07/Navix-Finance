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

import { TrustStars } from "@/components/staff/trust-stars";
import * as React from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { FilterBar } from "@/components/staff/filter-bar";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister } from "@/lib/staff/use-server-register";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { ExportMenu } from "@/components/staff/export-menu";
import { useQueueSelection } from "@/components/staff/pipeline/bulk-actions";
import { useStaffMe, useCan } from "@/components/staff/pipeline/hooks";
import { BulkAssignOfficerDialog, InlineOfficerSelect } from "@/components/staff/collections-assign";
import { CollectionsHeadDesk } from "@/components/staff/collections/head-desk";
import { pageTitleFor } from "@/components/staff/staff-nav";
import { AdminLogPaymentButton } from "@/components/staff/admin-log-payment";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import {
  collectionsApi,
  customersApi,
  paiseToINR,
  type CollectionHandoverRow,
  type CustomerSummary,
  type DpdBucket,
  type WorklistRow,
} from "@/lib/api/applications";
import { collectionHandoverColumns } from "@/lib/export/collection-handover-columns";
import { BUCKET_TONE, COLLECTION_BUCKETS, isDpdBucket } from "@/lib/collection-buckets";

const BUCKET_LABEL = Object.fromEntries(COLLECTION_BUCKETS.map((b) => [b.bucket, b.label])) as Record<DpdBucket, string>;

/** The register's scope: one bucket, or every live loan across all of them (the default). */
type Scope = DpdBucket | "ALL";
import { TONE_SOLID, gradientCss } from "@/components/staff/dashboard/colors";
import { worklistDueCue, worklistDueLabel, type WorklistDueCue } from "@/lib/collections/worklist-due";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { StageSignal } from "@/components/staff/stage-signal";
import { formatDate, formatDateTime } from "@/lib/utils";

/** Sortable + filterable columns. Identity (customer id, loan id, mobile, PAN) is sortable where it
 *  was but deliberately not filterable — the search box is how you find one borrower. */
const SORT_KEYS = [
  "customerId", "borrowerName", "loanId", "principalPaise", "outstandingPaise", "dueDate", "dpd",
  "bucketLabel", "employer", "salaryPaise", "creditDecidedByName", "disbursedByName", "officerName",
] as const;

/** Columns offering a filter dropdown — every sortable one except the identity columns. */
const FILTER_KEYS = [
  "borrowerName", "principalPaise", "outstandingPaise", "dueDate", "dpd", "bucketLabel", "employer",
  "salaryPaise", "creditDecidedByName", "disbursedByName", "officerName",
] as const;

/** Header text per key — read by the headers, the filter panels and the export's "Filtered:" line. */
const COLUMN_LABEL: Record<string, string> = {
  customerId: "Customer ID",
  borrowerName: "Borrower",
  loanId: "Loan",
  principalPaise: "Principal",
  outstandingPaise: "Outstanding",
  dueDate: "Due",
  dpd: "DPD",
  bucketLabel: "Bucket",
  employer: "Employer",
  salaryPaise: "Salary",
  creditDecidedByName: "Credit exec",
  disbursedByName: "Disbursed by",
  officerName: "Collections exec",
};

/** Flattened row: the sort primitive compares top-level keys, and `loan` is a nested object. */
interface Row {
  loanId: number;
  bucket: DpdBucket;
  bucketLabel: string;
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
  trust?: WorklistRow["trust"];
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
    bucket: w.bucket,
    bucketLabel: BUCKET_LABEL[w.bucket] ?? w.bucket,
    applicationId: w.loan?.applicationId ?? null,
    customerId: w.loan?.customerId ?? null,
    dpd: w.dpd,
    preDue: w.preDue,
    caseId: w.caseId,
    borrowerName: w.loan?.borrowerName ?? null,
    trust: w.trust,
    mobile: w.loan?.mobile ?? null,
    pan: w.loan?.pan ?? null,
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

/** "Download all" reads the matching rows in server pages of this size (the server caps a page at 200). */
const EXPORT_PAGE_SIZE = 200;
/** A runaway guard, far above any real worklist (200 x 250 = 50,000 loans). */
const EXPORT_MAX_PAGES = 250;

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
  // No `?bucket=` (the plain sidebar link) means the whole book — the separate read-only
  // "DPD buckets (all)" page was merged into this one, so All loans is the landing scope.
  const bucket: Scope = isDpdBucket(raw) ? raw : "ALL";
  const meta = { label: bucket === "ALL" ? "All loans" : BUCKET_LABEL[bucket] };

  const me = useStaffMe().data;
  const role = me?.role;

  // One owner for search, column filters, sort, page and size — kept in the URL (this is the page's
  // only register, so it owns it; `?q=` from the global-search palette lands here too) and in the
  // staffer's remembered view. Filter state lives in this reducer, never in the polled query data.
  const rq = useRegisterQuery({
    registerId: "collections",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: FILTER_KEYS,
    // Most overdue first: the top of a collections list should be the loan that has run longest.
    defaults: { sort: { key: "dpd", dir: "desc" } },
  });
  const query = rq.state.q;

  // The pop-up opens on the Repayment tab. `?open=<loanId>` deep links (notifications, the old
  // `/staff/collections/<loanId>` case URL) open it too, resolving the application from the loan.
  const openParam = Number(search.get("open"));
  const [openLoan, setOpenLoan] = React.useState<{ loanId: number; applicationId: number | null } | null>(
    Number.isFinite(openParam) && openParam > 0 ? { loanId: openParam, applicationId: null } : null,
  );
  // `?tab=collections` (from the case-URL redirect) opens that tab; the DPD row Open stays on Repayment.
  const [openTab, setOpenTab] = React.useState(search.get("tab") === "collections" ? "collections" : "repayment");
  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = rangeFor(period, custom);

  // The bucket cards come from their own aggregate, separate from the register: they are a map of the
  // entire (scoped) book AND this page's navigation, so they ignore search, window and column filters
  // and must not shrink as the register is used. The balance is the server's derived outstanding, so it
  // cannot be summed from a page. Under `["collections-worklist"]` so assign / log-payment invalidations
  // refresh it; the register itself polls at its own, faster interval.
  const q = useQuery({
    queryKey: ["collections-worklist", "summary"],
    queryFn: () => collectionsApi.worklistSummary(),
    refetchInterval: 30_000,
  });

  // Page-level scope: not part of `rq.queryParams`, so they ride as the register's own server params
  // AND in the query-key prefix — without them a bucket or window change would be answered by the
  // previous view's cached page.
  const scopeParams = React.useMemo(() => {
    const p: Record<string, string> = {};
    if (bucket !== "ALL") p.bucket = bucket;
    if (range.from) p.from = range.from;
    if (range.to) p.to = range.to;
    return p;
  }, [bucket, range.from, range.to]);

  // The register: one page of rows, the server's count, and facets fetched only when a column's
  // filter panel opens (`cr.requestFacet`, never on mount). The 8s poll is React Query's per-key
  // refetch, so it can only replace the rows of the view it was issued for; filter state is in
  // `rq`'s reducer and is never read back from this data.
  // Checklist labels: officer ids show as names (active officers, plus any seen on a page — a former
  // officer still assigned to a loan), and the blank value reads "Unallocated".
  const officersQ = useQuery({ queryKey: ["collections-officers"], queryFn: collectionsApi.listOfficers, staleTime: 60_000 });
  const officerLabels = React.useRef(new Map<string, string>());
  const facetLabels = React.useCallback(
    (column: string) => {
      if (column === "bucketLabel") return new Map(Object.entries(BUCKET_LABEL));
      if (column !== "officerName") return new Map<string, string>();
      const m = new Map<string, string>(officerLabels.current);
      for (const o of officersQ.data ?? []) m.set(String(o.id), o.name);
      m.set("__NULL__", "Unallocated");
      return m;
    },
    [officersQ.data],
  );
  const cr = useServerRegister<WorklistRow>(rq, {
    // Under `["collections-worklist"]` so the assign / log-payment invalidations refresh the register too.
    key: ["collections-worklist", "register", bucket, range.from ?? "", range.to ?? ""],
    fetchPage: (params, pg, size) => collectionsApi.worklistPage({ filters: params, page: pg, size, extra: scopeParams }),
    fetchFacet: (col, params) => collectionsApi.worklistFacet(col, { ...params, ...scopeParams }),
    labels: facetLabels,
    refetchInterval: 8000,
  });
  const { total, pageCount } = cr;
  const pageRows = React.useMemo(() => {
    const rows = cr.rows.map(toRow);
    for (const r of rows) if (r.officerId != null && r.officerName) officerLabels.current.set(String(r.officerId), r.officerName);
    return rows;
  }, [cr.rows]);

  // Export-only enrichment (mobile, bank, credit score, signup/history, and the agency-handover
  // blocks) the worklist snapshot doesn't carry, joined in by customer / application id. It is read
  // ONLY by the <ExportMenu> columns, which render nothing for a non-ADMIN, so it loads when the menu
  // opens (`onOpen`, for the page on screen) or when "download all" runs (for every matching row) —
  // never on mount. Held in a ref because the export reads it AFTER an await, from column closures
  // captured before it resolved; state would hand those closures the stale, empty maps.
  const enrichment = React.useRef<{ customers: Map<number, CustomerSummary>; handover: Map<number, CollectionHandoverRow> }>({
    customers: new Map(),
    handover: new Map(),
  });
  const [enriching, setEnriching] = React.useState(false);
  const enrich = React.useCallback(
    async (rows: Row[]) => {
      const customerIds = [...new Set(rows.map((r) => r.customerId).filter((v): v is number => v != null))].sort((a, b) => a - b);
      const applicationIds = [...new Set(rows.map((r) => r.applicationId).filter((v): v is number => v != null))].sort((a, b) => a - b);
      // `fetchQuery` honours `staleTime`, so reopening the menu on the same id set is a cache read.
      const [customers, handoverRows] = await Promise.all([
        customerIds.length
          ? qc.fetchQuery({
              queryKey: ["collections-export-customers", customerIds],
              queryFn: () => customersApi.byIdsAll(customerIds),
              staleTime: EXPORT_ENRICHMENT_STALE_MS,
            })
          : Promise.resolve([] as CustomerSummary[]),
        applicationIds.length
          ? qc.fetchQuery({
              queryKey: ["collections-export-handover", applicationIds],
              queryFn: () => customersApi.collectionHandoverAll(applicationIds),
              staleTime: EXPORT_ENRICHMENT_STALE_MS,
            })
          : Promise.resolve([] as CollectionHandoverRow[]),
      ]);
      enrichment.current = {
        customers: new Map(customers.map((c) => [c.customerId, c])),
        handover: new Map(handoverRows.map((h) => [h.applicationId, h])),
      };
    },
    [qc],
  );
  const customerFor = (id: number | null) => (id != null ? enrichment.current.customers.get(id) : undefined);
  const handover = React.useMemo(
    () => collectionHandoverColumns<Row>((id) => (id != null ? enrichment.current.handover.get(id) : undefined)),
    [],
  );

  // "Download all": every row matching the CURRENT view (same search, bucket, window, column filters,
  // sort), fetched page by page from the server — `rows` on this register is one page, so the export
  // cannot take it from there. Deduped by loan because the register polls: a loan can move between two
  // page reads.
  const fetchAllRows = React.useCallback(async (): Promise<Row[]> => {
    const byLoan = new Map<number, Row>();
    for (let pg = 1; pg <= EXPORT_MAX_PAGES; pg++) {
      const res = await collectionsApi.worklistPage({ filters: rq.queryParams, page: pg, size: EXPORT_PAGE_SIZE, extra: scopeParams });
      for (const w of res.rows) byLoan.set(w.loanId, toRow(w));
      if (res.rows.length === 0 || byLoan.size >= res.total) break;
    }
    const all = [...byLoan.values()];
    await enrich(all);
    return all;
  }, [rq.queryParams, scopeParams, enrich]);

  // Counts across ALL buckets, from the unfiltered set — the header cards are a map of the whole
  // book, so they must not move when the operator narrows one bucket.
  const counts = React.useMemo(() => {
    const c: Record<string, { n: number; outstandingPaise: number }> = {};
    for (const { bucket: b } of COLLECTION_BUCKETS) c[b] = { n: 0, outstandingPaise: 0 };
    for (const w of q.data ?? []) {
      const slot = c[w.bucket];
      if (slot) {
        slot.n += w.n;
        slot.outstandingPaise += w.outstandingPaise;
      }
    }
    return c;
  }, [q.data]);

  // Totals for the All loans card — the sum of every bucket, so it never moves with the open scope.
  const allCount = React.useMemo(
    () =>
      Object.values(counts).reduce(
        (acc, c) => ({ n: acc.n + c.n, outstandingPaise: acc.outstandingPaise + c.outstandingPaise }),
        { n: 0, outstandingPaise: 0 },
      ),
    [counts],
  );

  // What each column offers. `raw` carries the stored value wherever the cell shows a label, so a
  // filter never travels as the words on screen; `num` is in RUPEES, not paise, because the person
  // typing a bound is reading the rupee column beside it.
  const baseColumns = React.useMemo<Array<FilterColumn<Row>>>(
    () => [
      { key: "borrowerName", kind: "text", value: (r) => r.borrowerName ?? "" },
      { key: "principalPaise", kind: "num", value: (r) => (r.principalPaise != null ? paiseToINR(r.principalPaise) : "—"), num: (r) => (r.principalPaise != null ? r.principalPaise / 100 : null) },
      { key: "outstandingPaise", kind: "num", value: (r) => (r.outstandingPaise != null ? paiseToINR(r.outstandingPaise) : "—"), num: (r) => (r.outstandingPaise != null ? r.outstandingPaise / 100 : null) },
      { key: "dueDate", kind: "date", value: (r) => (r.dueDate ? formatDate(r.dueDate) : "—"), date: (r) => istDay(r.dueDate) },
      // The server's day count (0 while not yet due), not the "due in 3 d" cue the cell can show —
      // that cue depends on the clock, a bound typed into a filter must not.
      { key: "dpd", kind: "num", value: (r) => String(r.dpd), num: (r) => r.dpd },
      // `bucketLabel` renders T0_T7 as "1–7 DPD"; the filter travels as the stored enum.
      { key: "bucketLabel", facet: true, value: (r) => r.bucketLabel, raw: (r) => r.bucket },
      { key: "employer", kind: "text", value: (r) => r.employer ?? "" },
      { key: "salaryPaise", kind: "num", value: (r) => (r.salaryPaise != null ? paiseToINR(r.salaryPaise) : "—"), num: (r) => (r.salaryPaise != null ? r.salaryPaise / 100 : null) },
      // The worklist carries only the names of the credit exec and the disbursing head, not their
      // ids, so these key on the name; "no one recorded" is a value people filter for. Both are
      // DERIVED (read off the event trail), so the server filters them in Java, not SQL.
      { key: "creditDecidedByName", facet: true, value: (r) => r.creditDecidedByName ?? "Not recorded", raw: (r) => r.creditDecidedByName ?? "" },
      { key: "disbursedByName", facet: true, value: (r) => r.disbursedByName ?? "Not recorded", raw: (r) => r.disbursedByName ?? "" },
      {
        key: "officerName",
        facet: true,
        value: (r) => r.officerName ?? "Unallocated",
        // Keyed on the officer ID (blank = unallocated), so two officers who share a display name stay
        // apart. The id is also the server's scope column; that is safe because column filters are
        // ANDed after scope and can only narrow. The checklist SHOWS the name (see `labels`).
        raw: (r) => (r.officerId != null ? String(r.officerId) : ""),
      },
    ],
    [],
  );

  // A facet the server had to truncate cannot offer a complete checklist, so it degrades to a text
  // filter instead of a dropdown that silently omits values.
  const filterColumns = React.useMemo(
    () => baseColumns.map((c) => (c.facet && cr.facetTruncated(c.key) && c.key === "bucketLabel"
      ? { ...c, kind: "text" as const } : c)),
    [baseColumns, cr],
  );
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL, cr.requestFacet);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  // The bucket, the search box and the date window are the register's SERVER-side prefilter, applied
  // before the column filters. A new bucket or window starts again at page 1 — reset during the
  // render that first sees it (React discards that render and re-runs it with page 1), because an
  // effect would let one request through for the new scope at the OLD page. Same shape as the
  // customers register's scope switch.
  const urlScope = `${bucket}|${range.from ?? ""}|${range.to ?? ""}`;
  const [pagedScope, setPagedScope] = React.useState(urlScope);
  if (pagedScope !== urlScope) {
    setPagedScope(urlScope);
    rq.setPage(1);
  }

  // One reading of "now" per render for the DPD cell's days-to-due; the 8s poll re-renders, so it
  // never drifts more than a poll behind. The helper measures it in IST.
  const now = new Date();

  // Bulk assign of a collections executive. `collections:manage` is COLLECTION_HEAD + ADMIN — the
  // same pair the per-case assign endpoint already permits, so the button and the server agree.
  const can = useCan();
  const canBulkAssign = role != null && can("collections:manage");
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

  /**
   * Move to a bucket, keeping every other parameter.
   *
   * The cards used to `push("/staff/collections?bucket=X")`, which rebuilds the query string from
   * nothing: the register's filters, sort and page size went with it, and so did an open `?open=`
   * / `?tab=` deep link — the register's own effect put ITS params back a tick later, so the URL
   * briefly described a view nobody was looking at and the pushed history entry kept that wrong
   * URL forever. Same shape as the loans register's `?seg=` switch.
   */
  function goToBucket(next: DpdBucket | "ALL") {
    const params = new URLSearchParams(search.toString());
    if (next === "ALL") params.delete("bucket");
    else params.set("bucket", next);
    const qs = params.toString();
    router.push(qs ? `/staff/collections?${qs}` : "/staff/collections");
  }

  return (
    <div>
      <PageHeader
        title={`${pageTitleFor("/staff/collections", role, "Collections")} · ${meta.label}`}
        subtitle={[
          "Every live loan in this bucket — past due, or falling due within the week. DPD is computed on read.",
          describeRegister(th),
        ]
          .filter(Boolean)
          .join(" · ")}
      >
        <ExportMenu<Row>
          title={`Collections — ${meta.label}`}
          fileBase={`collections-${bucket.toLowerCase()}`}
          subtitle={describeRegister(th) || undefined}
          rows={pageRows}
          // `rows` is ONE server page, so the menu names it "page"; the full matching set comes from
          // `getAllRows`, which re-asks the server under the same search, bucket, window, column
          // filters and sort — so "all" carries exactly what the table is filtered to.
          scope="page"
          getAllRows={fetchAllRows}
          allLabel="Download all loans (CSV)"
          onOpen={() => {
            setEnriching(true);
            enrich(pageRows)
              .catch(() => undefined) // a failed enrichment just leaves those cells "—"
              .finally(() => setEnriching(false));
          }}
          disabled={enriching}
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
            { header: "Bucket", value: (r) => r.bucketLabel },
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
          onClick={() => {
            cr.refetch();
            void q.refetch();
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100"
        >
          {cr.isPlaceholderData || q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* The Head's desk — unassigned loans and the team's day — renders only for `collections:manage`
          (COLLECTION_HEAD / ADMIN); a count narrows the register through the officer column filter. */}
      <CollectionsHeadDesk
        onFilterOfficer={(staffId) => rq.setFilter("officerName", { type: "in", values: [String(staffId)] })}
      />

      {/* Bucket cards double as the switcher — the old page hid a <select> behind `lg:hidden`, so on
          desktop there was no way to move between buckets from the page itself. */}
      <div className="mb-4 grid grid-cols-2 gap-2 md:grid-cols-4 lg:grid-cols-7">
        <button
          type="button"
          aria-pressed={bucket === "ALL"}
          onClick={() => goToBucket("ALL")}
          style={{ background: gradientCss("teal") }}
          className={`rounded-lg p-3 text-left text-white shadow-sm transition ${
            bucket === "ALL"
              ? "ring-2 ring-navy ring-offset-2"
              : "opacity-60 hover:-translate-y-0.5 hover:opacity-100 hover:shadow-md"
          }`}
        >
          <p className="text-xs font-semibold text-white/90">All loans</p>
          <p className="font-serif text-2xl font-bold text-white">{allCount.n}</p>
          <p className="text-xs text-white/90">{paiseToINR(allCount.outstandingPaise)}</p>
        </button>
        {COLLECTION_BUCKETS.map((item) => {
          const active = item.bucket === bucket;
          const c = counts[item.bucket] ?? { n: 0, outstandingPaise: 0 };
          return (
            // Same colours as the DPD overview cards; the open bucket is full strength with a ring,
            // the others are dimmed until hovered.
            <button
              key={item.bucket}
              type="button"
              aria-pressed={active}
              onClick={() => goToBucket(item.bucket)}
              style={{ background: gradientCss(BUCKET_TONE[item.bucket]) }}
              className={`rounded-lg p-3 text-left text-white shadow-sm transition ${
                active
                  ? "ring-2 ring-navy ring-offset-2"
                  : "opacity-60 hover:-translate-y-0.5 hover:opacity-100 hover:shadow-md"
              }`}
            >
              <p className="text-xs font-semibold text-white/90">{item.label}</p>
              <p className="font-serif text-2xl font-bold text-white">{c.n}</p>
              <p className="text-xs text-white/90">{paiseToINR(c.outstandingPaise)}</p>
            </button>
          );
        })}
      </div>

      <div className="mb-3 flex flex-wrap items-end gap-3">
        <SearchBar
          initialValue={query}
          onSearch={rq.setQ}
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

      {/* Below the cards / search / date window (navigation and prefilter, which keep their own
          unfiltered meaning), directly above the table. Clear drops the column filters only. */}
      <FilterBar th={th} total={total} onClear={() => rq.setFilters({})} />

      {/* The panel look lives on this outer wrapper so `PaginationBar` can sit AFTER the scroller,
          not inside it — bounding the scroller (`staff-register-scroll`) would otherwise scroll the
          pagination away with the rows. */}
      <div className="rounded border border-line bg-white shadow-sm">
        {cr.error ? (
          <ErrorState error={cr.error} onRetry={() => cr.refetch()} />
        ) : cr.isLoading ? (
          <Skeleton variant="table" rows={8} cols={canBulkAssign ? 20 : 19} />
        ) : (
          // Offset clears the shell header, PageHeader, the bucket-card row, the search/date/bulk
          // toolbar and the filter strip (`--filter-bar`) above the register.
          <div
            // While a new filter/sort/page is in flight the rows on screen belong to the PREVIOUS view.
            aria-busy={cr.isPlaceholderData}
            className={`staff-table-scroll staff-register-scroll${cr.isPlaceholderData ? " opacity-60" : ""}`}
            style={{ "--register-offset": "calc(30rem + var(--filter-bar))" } as React.CSSProperties}
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
                  <Th th={th} col="customerId" />
                  <Th th={th} col="borrowerName" className={canBulkAssign ? undefined : "staff-sticky-identity"} />
                  <th scope="col">Signals</th>
                  <th scope="col">Mobile</th>
                  <th scope="col">PAN</th>
                  <Th th={th} col="loanId" />
                  <Th th={th} col="principalPaise" className="num" />
                  <Th th={th} col="outstandingPaise" className="num" />
                  <Th th={th} col="dueDate" />
                  <Th th={th} col="dpd" className="num" />
                  <Th th={th} col="bucketLabel" />
                  <th scope="col">Stage</th>
                  <Th th={th} col="employer" />
                  <Th th={th} col="salaryPaise" className="num" />
                  <Th th={th} col="creditDecidedByName" />
                  <Th th={th} col="disbursedByName" />
                  <Th th={th} col="officerName" />
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
                    <td><TrustStars trust={r.trust} /></td>
                    <td className="font-mono text-xs">{dash(r.mobile)}</td>
                    <td className="font-mono text-xs">{dash(r.pan)}</td>
                    <td className="font-mono">#{r.loanId}</td>
                    <td className="num font-mono">{paiseToINR(r.principalPaise)}</td>
                    <td className="num font-mono font-semibold">{paiseToINR(r.outstandingPaise)}</td>
                    <td className={r.dpd > 0 ? "font-semibold text-error-700" : undefined}>
                      {r.dueDate ? formatDate(r.dueDate) : "—"}
                    </td>
                    <DpdCell row={r} now={now} />
                    <td>
                      <span
                        className="inline-block whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold text-white"
                        style={{ background: TONE_SOLID[BUCKET_TONE[r.bucket]] }}
                      >
                        {r.bucketLabel}
                      </span>
                    </td>
                    <td>
                      <StageSignal compact status="ACTIVE" loanStatus={r.loanStatus} dpd={r.dpd} />
                    </td>
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
                        <button
                          onClick={() => {
                            setOpenTab("repayment");
                            setOpenLoan({ loanId: r.loanId, applicationId: r.applicationId });
                          }}
                          className="btn btn-sm btn-outline"
                        >
                          Open <ArrowRight size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {total === 0 ? (
                  // In-table, not instead of the table: an empty result must still show the headers,
                  // or there is no way to clear the column filter that emptied it.
                  <EmptyState
                    title={
                      rq.activeCount > 0
                        ? `No loans in ${meta.label} match the column filters.`
                        : `No loans in ${meta.label}${query.trim() ? ` for “${query.trim()}”` : ""}${
                            period !== "ALL" ? " in the selected date range" : ""
                          }.`
                    }
                    inTable={canBulkAssign ? 20 : 19}
                  />
                ) : null}
              </tbody>
            </table>
          </div>
        )}
        {total > 0 && (
          <PaginationBar
            page={page}
            setPage={rq.setPage}
            pageSize={pageSize}
            setPageSize={rq.setSize}
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
        applicationId={openLoan?.applicationId ?? null}
        loanId={openLoan?.loanId ?? null}
        initialTab={openTab}
        onClose={() => {
          setOpenLoan(null);
          // Drop `?open=` so a refresh does not reopen what was just closed.
          if (search.has("open")) {
            const next = new URLSearchParams(search.toString());
            next.delete("open");
            next.delete("tab");
            router.replace(next.size ? `/staff/collections?${next}` : "/staff/collections", { scroll: false });
          }
        }}
      />
    </div>
  );
}
