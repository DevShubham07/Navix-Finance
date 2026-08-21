"use client";

import * as React from "react";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, ArrowRight, ChevronDown, ChevronRight as ChevronRightIcon } from "lucide-react";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { Input } from "@/components/ui";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage, useStaffMe, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { LoanDetailDialog } from "@/components/staff/loan-detail-dialog";
import { loansApi, paiseToINR, type LoanRegisterRow } from "@/lib/api/applications";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { useTableSort, SortableTh, type SortDir } from "@/components/staff/sortable-table";
import {
  SEGMENTS,
  SEGMENT_LABEL,
  SEGMENT_TONE,
  segmentOf,
  inSegment,
  segmentCounts,
  type LoanSegment,
} from "@/lib/loans/segments";
import { formatDate } from "@/lib/utils";

/** Date columns that make sense to group rows under — the other sortable columns (amount/DPD) would
 *  interleave dates meaninglessly if we tried to header-group by them, so grouping is switched off
 *  for those (see `groupFieldFor` below). */
const DATE_SORT_KEYS = new Set(["dueDate", "sanctionedAt", "disbursedOn"]);

/** Sortable columns — kept in one place so the URL-param validation and the `<SortableTh>` calls
 *  can't drift apart. */
const SORT_KEYS = ["dueDate", "sanctionedAt", "disbursedOn", "principalPaise", "outstandingPaise", "dpd"] as const;
type SortKey = (typeof SORT_KEYS)[number];

function isSortKey(v: string | null): v is SortKey {
  return !!v && (SORT_KEYS as readonly string[]).includes(v);
}

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

/**
 * Loans register — every disbursed loan, past and present, company-wide. ADMIN + COLLECTION_HEAD
 * only (`loan:register`). Mirrors the Customers page's shape (search+range server-side, segment
 * chip client-side, collapsible date-group header rows, sticky identity/actions columns) but every
 * row here already IS a loan, so there's no application-status fallback to reconcile.
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

  const sortParam = searchParams.get("sort");
  const dirParam = searchParams.get("dir");
  const initialSortKey: SortKey = isSortKey(sortParam) ? sortParam : "dueDate";
  const initialDir: SortDir = dirParam === "asc" ? "asc" : "desc";

  const [search, setSearch] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [openLoanId, setOpenLoanId] = React.useState<number | null>(null);
  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = React.useMemo(() => rangeFor(period, custom), [period, custom]);

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  // Search + date range are server-side (mirrors customersApi.list); the segment chip and the sort
  // are both client-side and compose independently on top of whatever the server returned.
  const q = useQuery({
    queryKey: ["staff-loans", debounced, range.from ?? "", range.to ?? ""],
    queryFn: () => loansApi.list(debounced || undefined, range),
  });

  const rows = React.useMemo(() => q.data ?? [], [q.data]);
  const counts = React.useMemo(() => segmentCounts(rows), [rows]);
  const filtered = React.useMemo(() => rows.filter((r) => inSegment(r, seg)), [rows, seg]);

  const { sorted, sortKey, dir, toggle } = useTableSort<LoanRegisterRow>(filtered, initialSortKey, initialDir);

  // Persist sort in the URL (next to ?seg=) so a view is linkable and survives reload. Defaults
  // (dueDate desc) are omitted from the query string, same convention as ?seg=all being omitted.
  React.useEffect(() => {
    const p = new URLSearchParams(searchParams.toString());
    if (sortKey === "dueDate") p.delete("sort");
    else p.set("sort", sortKey);
    if (dir === "desc") p.delete("dir");
    else p.set("dir", dir);
    const qs = p.toString();
    const next = qs ? `${pathname}?${qs}` : pathname;
    // Only replace when the URL actually needs to change — avoids a redundant history entry.
    const current = searchParams.toString();
    if (qs !== current) router.replace(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sortKey, dir]);

  function setSeg(next: LoanSegment) {
    const p = new URLSearchParams(searchParams.toString());
    if (next === "all") p.delete("seg");
    else p.set("seg", next);
    const qs = p.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
  }

  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(sorted);

  // Grouping follows the active date sort; switched off entirely for amount/DPD sorts, where date
  // headers would interleave meaninglessly (see DATE_SORT_KEYS).
  const groupField = DATE_SORT_KEYS.has(sortKey) ? (sortKey as "dueDate" | "sanctionedAt" | "disbursedOn") : null;

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
          subtitle={seg === "all" ? "All loans" : SEGMENT_LABEL[seg]}
          fileBase="dhanboost-loans"
          columns={[
            { header: "Loan #", value: (l: LoanRegisterRow) => l.loanId },
            { header: "Borrower", value: (l) => l.borrowerName },
            { header: "Mobile", value: (l) => l.mobile },
            { header: "PAN", value: (l) => l.panMasked },
            { header: "Cycle", value: (l) => ordinal(l.loanCycle) },
            { header: "Sanctioned", value: (l) => (l.sanctionedAt ? formatDate(l.sanctionedAt) : "") },
            { header: "Disbursed", value: (l) => (l.disbursedOn ? formatDate(l.disbursedOn) : "") },
            { header: "Due", value: (l) => (l.dueDate ? formatDate(l.dueDate) : "") },
            { header: "Closed", value: (l) => (l.closedOn ? formatDate(l.closedOn) : "") },
            { header: "Principal (₹)", value: (l) => (l.principalPaise / 100).toFixed(2) },
            { header: "Net disbursed (₹)", value: (l) => (l.netDisbursedPaise / 100).toFixed(2) },
            { header: "Repayable (₹)", value: (l) => (l.totalRepayablePaise / 100).toFixed(2) },
            { header: "Outstanding (₹)", value: (l) => (l.outstandingPaise / 100).toFixed(2) },
            { header: "DPD", value: (l) => l.dpd },
            { header: "Status", value: (l) => loanStatusLabel(l.status) },
            { header: "Officer", value: (l) => l.assignedOfficerName ?? "" },
            { header: "Disbursal ref", value: (l) => l.disbursalTxnRef ?? "" },
          ]}
          rows={sorted}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
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
          <Input
            aria-label="Search loans"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Borrower, mobile, PAN, loan or application ID"
            leftIcon={<Search size={15} />}
            className="!mb-0"
            inputClassName="w-80"
          />
          <QueueDateFilter period={period} setPeriod={setPeriod} custom={custom} setCustom={setCustom} />
          {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
        </div>

        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <div className="h-40 animate-pulse rounded bg-grey-100" />
          ) : q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : filtered.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-muted">
              No loans{debounced ? ` for “${debounced}”` : ""}{seg !== "all" ? ` in ${SEGMENT_LABEL[seg]}` : ""}
              {period !== "ALL" ? " in the selected date range" : ""}.
            </p>
          ) : (
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th className="staff-sticky-identity">Loan</th>
                  <th>Borrower</th>
                  <SortableTh label="Sanctioned" sortKey="sanctionedAt" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh label="Disbursed" sortKey="disbursedOn" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh label="Due" sortKey="dueDate" active={sortKey} dir={dir} onToggle={toggle} />
                  <th>Cycle</th>
                  <SortableTh label="Principal" sortKey="principalPaise" active={sortKey} dir={dir} onToggle={toggle} />
                  <th>Net disbursed</th>
                  <th>Repayable</th>
                  <SortableTh label="Outstanding" sortKey="outstandingPaise" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh label="DPD" sortKey="dpd" active={sortKey} dir={dir} onToggle={toggle} />
                  <th>Status</th>
                  <th>Officer</th>
                  <th className="staff-sticky-actions text-right">Open</th>
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
                            <td colSpan={15} className="bg-grey-50 px-3 py-2">
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
                                <button onClick={() => setOpenLoanId(l.loanId)} className="font-semibold text-navy hover:underline">
                                  #{l.loanId}
                                </button>
                              </td>
                              <td className="staff-cell" title={l.borrowerName}>
                                <span className="block truncate font-medium text-ink">{l.borrowerName}</span>
                                <span className="block truncate text-xs text-muted">{l.mobile} · {l.panMasked}</span>
                              </td>
                              <td className="whitespace-nowrap text-muted">{l.sanctionedAt ? formatDate(l.sanctionedAt) : "—"}</td>
                              <td className="whitespace-nowrap text-muted">{l.disbursedOn ? formatDate(l.disbursedOn) : "—"}</td>
                              <td className="whitespace-nowrap">
                                <span className={l.dpd > 0 ? "font-semibold text-error-700" : "text-ink"}>
                                  {l.dueDate ? formatDate(l.dueDate) : "—"}
                                </span>
                              </td>
                              <td className="text-muted">{ordinal(l.loanCycle)}</td>
                              <td className="font-mono text-ink">{paiseToINR(l.principalPaise)}</td>
                              <td className="font-mono text-ink">{paiseToINR(l.netDisbursedPaise)}</td>
                              <td className="font-mono text-ink">{paiseToINR(l.totalRepayablePaise)}</td>
                              <td className="font-mono font-semibold text-ink">{paiseToINR(l.outstandingPaise)}</td>
                              <td className={l.dpd > 0 ? "font-semibold text-error-700" : "text-muted"}>{l.dpd > 0 ? `${l.dpd}d` : "—"}</td>
                              <td>
                                <Badge variant={SEGMENT_TONE[segmentOf(l)]} size="sm">
                                  {loanStatusLabel(l.status)}
                                </Badge>
                              </td>
                              <td className="staff-cell text-ink">{l.assignedOfficerName ?? <span className="text-muted">Unallocated</span>}</td>
                              <td className="staff-sticky-actions text-right">
                                <button onClick={() => setOpenLoanId(l.loanId)} className="inline-flex items-center gap-1 text-navy hover:underline">
                                  Open <ArrowRight size={14} />
                                </button>
                              </td>
                            </tr>
                          ))}
                      </React.Fragment>
                    );
                  });
                })()}
              </tbody>
            </table>
          )}
          <PaginationBar page={page} pageCount={pageCount} setPage={setPage} total={total} pageSize={pageSize} setPageSize={setPageSize} />
        </div>
      </PermissionGate>

      <LoanDetailDialog loanId={openLoanId} onClose={() => setOpenLoanId(null)} />
    </div>
  );
}
