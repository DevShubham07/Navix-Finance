"use client";

import { TrustStars } from "@/components/staff/trust-stars";
import * as React from "react";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, ChevronDown, ChevronRight as ChevronRightIcon } from "lucide-react";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { PermissionGate, NoAccessNotice, useStaffMe, useCan, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { ExportMenu } from "@/components/staff/export-menu";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { AdminLogPaymentButton } from "@/components/staff/admin-log-payment";
import { loansApi, paiseToINR, type LoanRegisterRow } from "@/lib/api/applications";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { type FilterColumn } from "@/components/staff/column-filter";
import { FilterBar } from "@/components/staff/filter-bar";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { BLANK } from "@/lib/staff/column-filter-match";
import { retryUnlessClientError } from "@/lib/staff/admin-all-applications";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister } from "@/lib/staff/use-server-register";
import {
  SEGMENTS,
  SEGMENT_LABEL,
  SEGMENT_TONE,
  segmentOf,
  type LoanSegment,
} from "@/lib/loans/segments";
import { loanRegisterDpd, loanRegisterDpdLabel } from "@/lib/loans/loan-register-dpd";
import { formatDate } from "@/lib/utils";

/** Date columns that make sense to group rows under — the other sortable columns (amount/DPD) would
 *  interleave dates meaninglessly if we tried to header-group by them, so grouping is switched off
 *  for those (see `groupFieldFor` below). */
const DATE_SORT_KEYS = new Set(["dueDate", "sanctionedAt", "disbursedOn", "closedOn"]);

/** Sortable columns — kept in one place so the URL-param validation and the `<SortableTh>` calls
 *  can't drift apart. */
const SORT_KEYS = [
  "dueDate", "sanctionedAt", "disbursedOn", "closedOn", "principalPaise", "outstandingPaise", "dpd",
  "borrowerName", "loanCycle", "netDisbursedPaise", "totalRepayablePaise", "status", "assignedOfficerName",
] as const;

/** Columns offering a filter dropdown. The same keys the sort uses, so a header is one control. */
const FILTER_KEYS = SORT_KEYS;

/** Header text per key — read by the headers, the filter panels and the export's "Filtered:" line. */
const COLUMN_LABEL: Record<string, string> = {
  borrowerName: "Borrower",
  sanctionedAt: "Sanctioned",
  disbursedOn: "Disbursed",
  dueDate: "Due",
  closedOn: "Closure date",
  loanCycle: "Cycle",
  principalPaise: "Principal",
  netDisbursedPaise: "Net disbursed",
  totalRepayablePaise: "Repayable",
  outstandingPaise: "Outstanding",
  dpd: "DPD",
  status: "Status",
  assignedOfficerName: "Officer",
};

/** "1st" / "2nd" / "3rd" / "4th" ... from a 1-based loan cycle number. */
function ordinal(n: number): string {
  const v = n % 100;
  if (v >= 11 && v <= 13) return `${n}th`;
  switch (n % 10) {
    case 1:
      return `${n}st`;
    case 2:
      return `${n}nd`;
    case 3:
      return `${n}rd`;
    default:
      return `${n}th`;
  }
}

/** Readable label for the loan's raw backend status string (`IN_COLLECTIONS` → `In Collections`).
 *  Deliberately generic rather than a hand-maintained map — new statuses read sensibly with no code
 *  change, and the segment chip (`SEGMENT_TONE`) already carries the meaningful grouping/tone. */
function loanStatusLabel(status: string): string {
  return status
    .toLowerCase()
    .split("_")
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w))
    .join(" ");
}

/** Every stored loan status, so the Status checklist reads in words and not in enum names. */
const STATUS_VALUES = [
  "APPLIED", "UNDER_REVIEW", "APPROVED", "DOCS_SIGNED", "BANK_VERIFIED", "DISBURSING",
  "ACTIVE", "REPAID", "OVERDUE", "IN_COLLECTIONS", "CLOSED", "DECLINED",
];

/** Raw value → label for the two checklist columns. The officer's raw value is their NAME (the server
 *  filters the collections officer in Java, by display name — see `LoanRegisterService`), and "no
 *  officer" travels as the shared blank sentinel. */
const FACET_LABELS: Record<string, Record<string, string>> = {
  status: Object.fromEntries(STATUS_VALUES.map((v) => [v, loanStatusLabel(v)])),
  assignedOfficerName: { [BLANK]: "Unallocated" },
};

/** How many rows one export request asks for; the server caps a page at 500. */
const EXPORT_CHUNK = 500;

/** The open loan; `applicationId` is set when opened from a row (the register row carries it), so
 *  the pop-up skips the loan -> customer lookup a `?open=` deep link needs. */
type OpenLoan = { loanId: number; applicationId?: number | null };

function openFromRow(l: LoanRegisterRow): OpenLoan {
  return { loanId: l.loanId, applicationId: l.applicationId };
}

/** "12d" when late, "due today" / "not due" while current (IST day), "—" otherwise. */
function DpdCell({ row }: { row: LoanRegisterRow }) {
  const state = loanRegisterDpd(row);
  const tone =
    state.kind === "overdue"
      ? "font-semibold text-error-700"
      : state.kind === "due-today"
        ? "font-medium text-ink"
        : "text-muted";
  return <td className={`num whitespace-nowrap ${tone}`}>{loanRegisterDpdLabel(state)}</td>;
}

/**
 * Loans register — every disbursed loan, past and present, company-wide. ADMIN + COLLECTION_HEAD
 * only (`loan:register`). Mirrors the Customers page's shape (search, date range, segment chip,
 * column filters, sort and paging all server-side; collapsible date-group header rows within the
 * page; sticky identity/actions columns) but every row here already IS a loan, so there's no
 * application-status fallback to reconcile.
 */
export default function LoansPage() {
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded bg-grey-100" />}>
      <LoansPageInner />
    </React.Suspense>
  );
}

function LoansPageInner() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const segParam = (searchParams.get("seg") ?? "all") as LoanSegment;
  const seg: LoanSegment = segParam === "all" || SEGMENTS.includes(segParam) ? segParam : "all";

  // Deep link from the global-search palette: `?q=318&open=318` narrows the register to that loan
  // and opens its detail dialog, so a hit in the palette lands on the record itself. `?q=` is read
  // by the register hook below, along with the filters, the sort and the page.
  const openParam = Number(searchParams.get("open"));
  const me = useStaffMe().data;

  // One owner for search, column filters, sort, page and size — it keeps them in the URL (so a
  // filtered register is a link) and in this staffer's remembered view, and it returns to page 1 on
  // any change that moves which rows match, in the same update.
  const rq = useRegisterQuery({
    registerId: "loans",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: FILTER_KEYS,
    defaults: { sort: { key: "dueDate", dir: "desc" } },
  });
  const query = rq.state.q;
  // The open loan plus, when opened from a row, the ids that row already carries — so the dialog can
  // start the customer read without first waiting on the loan. A `?open=` deep link has only the id.
  const [openLoan, setOpenLoan] = React.useState<OpenLoan | null>(
    Number.isFinite(openParam) && openParam > 0 ? { loanId: openParam } : null,
  );
  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = React.useMemo(() => rangeFor(period, custom), [period, custom]);

  const can = useCan();
  // Behind the permission gate: no request until the session says who is signed in AND that they may
  // read the register, so a role without `loan:register` never fires a 403 on first paint.
  const allowed = me?.role ? can("loan:register") : false;

  // The chip, the date window and (below) the search/filters/sort are all asked of the SERVER; only
  // the page on screen is ever enriched. The chip and the window are page-level, not part of
  // `rq.queryParams`, so they sit in the key prefix — without them a chip change would be answered by
  // the previous chip's cached page.
  const baseExtra = React.useMemo<Record<string, string>>(() => {
    const e: Record<string, string> = {};
    if (seg !== "all") e.seg = seg;
    if (range.from) e.from = range.from;
    if (range.to) e.to = range.to;
    return e;
  }, [seg, range.from, range.to]);

  const cr = useServerRegister<LoanRegisterRow>(rq, {
    key: ["staff-loans", seg, range.from ?? "", range.to ?? ""],
    fetchPage: (params, pg, size) => loansApi.page({ filters: params, extra: baseExtra, page: pg, size }),
    // No facet is fetched on mount: a header's panel opening calls `cr.requestFacet`.
    fetchFacet: (col, params) => loansApi.facet(col, { ...baseExtra, ...params }),
    labels: FACET_LABELS,
    enabled: allowed,
    retry: retryUnlessClientError,
  });

  // The chip counts are deliberately UNFILTERED by the columns (they are also the navigation, so
  // they must not collapse as you use them), but they do follow the search and the date window, as
  // they did when they were counted over the rows. One tiny page per chip: the server counts, we only
  // read `total`. Every loan is in exactly one chip, so "All" is their sum.
  const countsQ = useQuery({
    queryKey: ["staff-loans", "counts", query, range.from ?? "", range.to ?? ""],
    queryFn: async () => {
      const window: Record<string, string> = {};
      if (range.from) window.from = range.from;
      if (range.to) window.to = range.to;
      const totals = await Promise.all(
        SEGMENTS.map((s) =>
          loansApi
            .page({ filters: query ? { q: query } : {}, extra: { ...window, seg: s }, page: 1, size: 1 })
            .then((r) => r.total),
        ),
      );
      const out = { all: 0, active: 0, overdue: 0, closed: 0 } as Record<LoanSegment, number>;
      SEGMENTS.forEach((s, i) => {
        out[s] = totals[i];
        out.all += totals[i];
      });
      return out;
    },
    enabled: allowed,
    retry: retryUnlessClientError,
    // Keep the last counts on screen while a new search or date range loads, instead of flashing
    // "(0)" on every chip. No identity in this key.
    placeholderData: keepPreviousData,
  });
  const counts = countsQ.data ?? { all: 0, active: 0, overdue: 0, closed: 0 };

  // What each column offers. `raw` is the stored value behind the label; `facet: true` means the
  // checklist comes from the facets endpoint, not from the rows (which are one page). Status and
  // officer are the only checklists: the rest are text, a day window or a bound, so they need no
  // value list — and no identity column has a dropdown. `num` bounds are RUPEES, not paise: the
  // person typing one is reading the rupee column beside it, and the server scales the operand. A
  // column whose facet came back `truncated` degrades to a text filter.
  //
  // Every column both filters and sorts on the server, so none declares `sortable: false`. Two of
  // them (Outstanding, Officer) do it in Java over the SQL result rather than in SQL — see
  // `LoanRegisterService.page` — which costs time, not capability.
  const filterColumns = React.useMemo<Array<FilterColumn<LoanRegisterRow>>>(() => {
    const cols: Array<FilterColumn<LoanRegisterRow>> = [
      { key: "borrowerName", kind: "text", value: (l) => l.borrowerName ?? "" },
      { key: "sanctionedAt", kind: "date", value: (l) => (l.sanctionedAt ? formatDate(l.sanctionedAt) : "—") },
      { key: "disbursedOn", kind: "date", value: (l) => (l.disbursedOn ? formatDate(l.disbursedOn) : "—") },
      { key: "dueDate", kind: "date", value: (l) => (l.dueDate ? formatDate(l.dueDate) : "—") },
      { key: "closedOn", kind: "date", value: (l) => (l.closedOn ? formatDate(l.closedOn) : "—") },
      { key: "loanCycle", kind: "num", value: (l) => ordinal(l.loanCycle) },
      { key: "principalPaise", kind: "num", value: (l) => paiseToINR(l.principalPaise) },
      { key: "netDisbursedPaise", kind: "num", value: (l) => paiseToINR(l.netDisbursedPaise) },
      { key: "totalRepayablePaise", kind: "num", value: (l) => paiseToINR(l.totalRepayablePaise) },
      { key: "outstandingPaise", kind: "num", value: (l) => paiseToINR(l.outstandingPaise) },
      { key: "dpd", kind: "num", value: (l) => loanRegisterDpdLabel(loanRegisterDpd(l)) },
      { key: "status", facet: true, value: (l) => loanStatusLabel(l.status), raw: (l) => l.status },
      {
        key: "assignedOfficerName",
        facet: true,
        value: (l) => l.assignedOfficerName ?? "Unallocated",
        raw: (l) => l.assignedOfficerName ?? "",
      },
    ];
    return cols.map((c) => (c.facet && cr.facetTruncated(c.key) ? { ...c, kind: "text" as const } : c));
  }, [cr]);

  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL, cr.requestFacet);
  const { pageRows, total, pageCount } = cr;
  const sortKey = rq.state.sort.key;
  const page = rq.state.page;
  const pageSize = rq.state.size;

  // A new segment or date window starts again at page 1 — the search box already does (onSearch
  // below). Picking "Custom" changes no window until its range is applied, so it keeps the page.
  function setSeg(next: LoanSegment) {
    const p = new URLSearchParams(searchParams.toString());
    if (next === "all") p.delete("seg");
    else p.set("seg", next);
    const qs = p.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
    // `apply` with an empty patch is the register's "what matches has changed" signal: it returns to
    // page 1 without touching the filters, in one update.
    if (next !== seg) rq.apply({});
  }
  function resetPageIfRangeChanges(next: QueueRange) {
    if ((next.from ?? "") !== (range.from ?? "") || (next.to ?? "") !== (range.to ?? "")) rq.apply({});
  }
  function changePeriod(next: QueuePeriod) {
    setPeriod(next);
    resetPageIfRangeChanges(rangeFor(next, custom));
  }
  function changeCustom(next: QueueRange) {
    setCustom(next);
    resetPageIfRangeChanges(rangeFor(period, next));
  }

  // Grouping follows the active date sort; switched off entirely for amount/DPD sorts, where date
  // headers would interleave meaninglessly (see DATE_SORT_KEYS).
  const groupField = DATE_SORT_KEYS.has(sortKey) ? (sortKey as "dueDate" | "sanctionedAt" | "disbursedOn" | "closedOn") : null;

  const [collapsedDates, setCollapsedDates] = React.useState<Set<string>>(new Set());
  const toggleDate = (key: string) =>
    setCollapsedDates((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  // Consecutive rows on the current page grouped by the sorted date column, or one ungrouped
  // "bucket" when grouping is off — either way the running S.No. is seeded at (page-1)*pageSize so
  // it stays correct across groups and pages (mirrors the Customers page).
  const dateGroups = React.useMemo(() => {
    if (!groupField) return [{ key: "__ungrouped__", rows: pageRows }];
    const list: { key: string; rows: LoanRegisterRow[] }[] = [];
    for (const r of pageRows) {
      const value = r[groupField];
      const key = value ? value.slice(0, 10) : "unknown";
      const last = list[list.length - 1];
      if (last && last.key === key) last.rows.push(r);
      else list.push({ key, rows: [r] });
    }
    return list;
  }, [pageRows, groupField]);

  const role = useStaffMe().data?.role;

  return (
    <div>
      <PageHeader title="Loans" subtitle="Every disbursed loan, past and present — search, filter and sort the full register.">
        <ExportMenu
          title="Loans register"
          subtitle={[seg === "all" ? "All loans" : SEGMENT_LABEL[seg], describeRegister(th)]
            .filter(Boolean)
            .join(" · ")}
          fileBase="dhanboost-loans"
          columns={[
            { header: "Loan #", value: (l: LoanRegisterRow) => l.loanId },
            { header: "Borrower", value: (l) => l.borrowerName },
            { header: "Mobile", value: (l) => l.mobile },
            { header: "PAN", value: (l) => l.pan },
            { header: "Cycle", value: (l) => ordinal(l.loanCycle) },
            { header: "Sanctioned", value: (l) => (l.sanctionedAt ? formatDate(l.sanctionedAt) : "") },
            { header: "Disbursed", value: (l) => (l.disbursedOn ? formatDate(l.disbursedOn) : "") },
            { header: "Due", value: (l) => (l.dueDate ? formatDate(l.dueDate) : "") },
            { header: "Closure date", value: (l) => (l.closedOn ? formatDate(l.closedOn) : "") },
            { header: "Principal (₹)", value: (l) => (l.principalPaise / 100).toFixed(2) },
            { header: "Net disbursed (₹)", value: (l) => (l.netDisbursedPaise / 100).toFixed(2) },
            { header: "Repayable (₹)", value: (l) => (l.totalRepayablePaise / 100).toFixed(2) },
            { header: "Outstanding (₹)", value: (l) => (l.outstandingPaise / 100).toFixed(2) },
            { header: "DPD", value: (l) => l.dpd },
            { header: "Status", value: (l) => loanStatusLabel(l.status) },
            { header: "Officer", value: (l) => l.assignedOfficerName ?? "" },
            { header: "Disbursal ref", value: (l) => l.disbursalTxnRef ?? "" },
          ]}
          rows={pageRows}
          // `rows` is ONE page, so the menu must say "this page"; the full set comes from
          // `getAllRows`, which re-asks the server under the same search, window, chip and column
          // filters (rq.queryParams) so "all" carries exactly what the table shows.
          scope="page"
          getAllRows={async () => {
            const all: LoanRegisterRow[] = [];
            for (let pg = 1; ; pg++) {
              const r = await loansApi.page({ filters: rq.queryParams, extra: baseExtra, page: pg, size: EXPORT_CHUNK });
              all.push(...r.rows);
              if (r.rows.length === 0 || all.length >= r.total) return all;
            }
          }}
          allLabel="Download all loans (CSV)"
        />
        <button
          onClick={() => {
            cr.refetch();
            void countsQ.refetch();
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {cr.isPlaceholderData || countsQ.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="loan:register" fallback={<NoAccessNotice />}>
        <div className="mb-3 flex flex-wrap gap-1.5">
          <button type="button" className={`cal-preset${seg === "all" ? " on" : ""}`} onClick={() => setSeg("all")}>
            {SEGMENT_LABEL.all} ({counts.all})
          </button>
          {SEGMENTS.map((s) => (
            <button
              key={s}
              type="button"
              className={`cal-preset${seg === s ? " on" : ""}${
                seg === s ? (SEGMENT_TONE[s] === "success" ? " cal-preset--success" : SEGMENT_TONE[s] === "error" ? " cal-preset--error" : "") : ""
              }`}
              onClick={() => setSeg(s)}
            >
              {SEGMENT_LABEL[s]} ({counts[s]})
            </button>
          ))}
        </div>

        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <SearchBar
            initialValue={query}
            onSearch={(t) => rq.setQ(t)}
            placeholder="Borrower, mobile, PAN, loan or application ID"
            ariaLabel="Search loans"
          />
          <QueueDateFilter period={period} setPeriod={changePeriod} custom={custom} setCustom={changeCustom} />
          {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
        </div>

        {/* `onClear` is setFilters({}), not clearAll(): clearAll also wipes the search box, and "Clear filters" must not. */}
        <FilterBar th={th} total={total} onClear={() => rq.setFilters({})} />

        <div className="rounded border border-line bg-white shadow-sm">
          {/* Under keepPreviousData an empty previous result must not read as "No loans for <the
              new term>" before the new response has said so — show the skeleton instead. */}
          {cr.isLoading || (cr.isPlaceholderData && pageRows.length === 0) ? (
            <Skeleton variant="table" rows={8} cols={16} />
          ) : cr.error ? (
            <ErrorState error={cr.error} onRetry={() => cr.refetch()} />
          ) : pageRows.length === 0 ? (
            <EmptyState
              title={`No loans${query ? ` for “${query}”` : ""}${seg !== "all" ? ` in ${SEGMENT_LABEL[seg]}` : ""}${
                period !== "ALL" ? " in the selected date range" : ""
              }.`}
            />
          ) : (
            // `staff-register-scroll` pins the header; PaginationBar sits after the scroller so it
            // does not scroll away with the rows. The offset clears the shell header, PageHeader, the
            // segment-chip row, the search/date toolbar and the filter-chip strip (`--filter-bar`).
            // Dimmed and aria-busy while the previous search/range's rows stand in for the next
            // one's (keepPreviousData), so they never look current (mirrors the transactions ledger).
            <div
              className={`staff-table-scroll staff-register-scroll transition-opacity ${cr.isPlaceholderData ? "opacity-60" : ""}`}
              style={{ "--register-offset": "calc(24rem + var(--filter-bar))" } as React.CSSProperties}
            >
              <table className="staff-data-table" aria-busy={cr.isPlaceholderData}>
                <caption className="sr-only">Loans register</caption>
                <thead>
                  <tr>
                    <th scope="col">S.No.</th>
                    <th scope="col" className="staff-sticky-identity">Loan</th>
                    <Th th={th} col="borrowerName" />
                    <th scope="col">Signals</th>
                    <Th th={th} col="sanctionedAt" />
                    <Th th={th} col="disbursedOn" />
                    <Th th={th} col="dueDate" />
                    <Th th={th} col="closedOn" />
                    <Th th={th} col="loanCycle" />
                    <Th th={th} col="principalPaise" className="num" />
                    <Th th={th} col="netDisbursedPaise" className="num" />
                    <Th th={th} col="totalRepayablePaise" className="num" />
                    <Th th={th} col="outstandingPaise" className="num" />
                    <Th th={th} col="dpd" className="num" />
                    <Th th={th} col="status" />
                    <Th th={th} col="assignedOfficerName" />
                    <th scope="col" className="staff-sticky-actions text-right">Open</th>
                  </tr>
                </thead>
                <tbody>
                  {(() => {
                    let running = (page - 1) * pageSize;
                    return dateGroups.map((group) => {
                      const isCollapsed = groupField ? collapsedDates.has(group.key) : false;
                      const groupRows = group.rows.map((l) => {
                        running += 1;
                        return { l, sno: running };
                      });
                      return (
                        <React.Fragment key={group.key}>
                          {groupField && (
                            <tr>
                              <td colSpan={17} className="bg-grey-50 px-3 py-2">
                                <button
                                  type="button"
                                  onClick={() => toggleDate(group.key)}
                                  className="flex items-center gap-1.5 font-semibold text-ink"
                                >
                                  {isCollapsed ? <ChevronRightIcon size={14} /> : <ChevronDown size={14} />}
                                  {group.key === "unknown" ? "Date unknown" : formatDate(group.key)} · {group.rows.length} loan
                                  {group.rows.length === 1 ? "" : "s"}
                                </button>
                              </td>
                            </tr>
                          )}
                          {!isCollapsed &&
                            groupRows.map(({ l, sno }) => (
                              <tr key={l.loanId} className="hover:bg-grey-50">
                                <td className="text-muted">{sno}</td>
                                <td className="staff-cell staff-sticky-identity">
                                  <button onClick={() => setOpenLoan(openFromRow(l))} className="font-semibold text-navy hover:underline">
                                    #{l.loanId}
                                  </button>
                                </td>
                                <td className="staff-cell" title={l.borrowerName}>
                                  <span className="block truncate font-medium text-ink">{l.borrowerName}</span>
                                  <span className="block truncate text-xs text-muted">{l.mobile} · {l.pan}</span>
                                </td>
                                <td><TrustStars trust={l.trust} /></td>
                                <td className="whitespace-nowrap text-muted">{l.sanctionedAt ? formatDate(l.sanctionedAt) : "—"}</td>
                                <td className="whitespace-nowrap text-muted">{l.disbursedOn ? formatDate(l.disbursedOn) : "—"}</td>
                                <td className="whitespace-nowrap">
                                  <span className={l.dpd > 0 ? "font-semibold text-error-700" : "text-ink"}>
                                    {l.dueDate ? formatDate(l.dueDate) : "—"}
                                  </span>
                                </td>
                                <td className="whitespace-nowrap text-muted">{l.closedOn ? formatDate(l.closedOn) : "—"}</td>
                                <td className="text-muted">{ordinal(l.loanCycle)}</td>
                                <td className="num font-mono text-ink">{paiseToINR(l.principalPaise)}</td>
                                <td className="num font-mono text-ink">{paiseToINR(l.netDisbursedPaise)}</td>
                                <td className="num font-mono text-ink">{paiseToINR(l.totalRepayablePaise)}</td>
                                <td className="num font-mono font-semibold text-ink">{paiseToINR(l.outstandingPaise)}</td>
                                <DpdCell row={l} />
                                <td>
                                  <Badge variant={SEGMENT_TONE[segmentOf(l)]} size="sm">
                                    {loanStatusLabel(l.status)}
                                  </Badge>
                                </td>
                                <td className="staff-cell text-ink">{l.assignedOfficerName ?? <span className="text-muted">Unallocated</span>}</td>
                                <td className="staff-sticky-actions text-right">
                                  <span className="inline-flex items-center gap-2">
                                    <AdminLogPaymentButton loanId={l.loanId} loanStatus={l.status} compact />
                                    <button onClick={() => setOpenLoan(openFromRow(l))} className="inline-flex items-center gap-1 text-navy hover:underline">
                                      Open <ArrowRight size={14} />
                                    </button>
                                  </span>
                                </td>
                              </tr>
                            ))}
                        </React.Fragment>
                      );
                    });
                  })()}
                </tbody>
              </table>
            </div>
          )}
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={rq.setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={rq.setSize}
            unitLabel="loans"
          />
        </div>
      </PermissionGate>

      <ApplicationDetailDialog
        applicationId={openLoan?.applicationId ?? null}
        loanId={openLoan?.loanId ?? null}
        initialTab="loan"
        onClose={() => {
          setOpenLoan(null);
          // Drop `?open=` so a refresh (or a back-navigation) doesn't reopen what was just closed.
          if (searchParams.has("open")) {
            const next = new URLSearchParams(searchParams.toString());
            next.delete("open");
            router.replace(next.size ? `${pathname}?${next}` : pathname, { scroll: false });
          }
        }}
      />
    </div>
  );
}
