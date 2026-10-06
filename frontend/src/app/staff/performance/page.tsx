"use client";

/**
 * Staff performance — what each employee actually got through over a period.
 *
 * Sibling of `/staff/my-decisions`, not under `/staff/admin/*`: Heads can open it too, and the
 * server decides the roster (ADMIN → the company, a Head → their team, anyone else → themselves).
 * Following my-decisions, the nav entry carries no permission for that reason — the data, not the
 * route, is what's scoped.
 *
 * Row → `/staff/my-decisions?staffId=…`, which already renders the per-action breakdown behind
 * these totals. There is deliberately no second drill-down UI here.
 */

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip as RTooltip,
  XAxis,
  YAxis,
} from "recharts";
import { FilterX } from "lucide-react";
import { PageHeader, StatCard, RefreshButton } from "@/components/staff/staff-ui";
import { ExportMenu } from "@/components/staff/export-menu";
import { NoAccessNotice, errMessage, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { useColumnFilters, FilterableTh, type FilterColumn } from "@/components/staff/column-filter";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { PeriodPicker } from "@/components/staff/period-picker";
import { InfoTooltip } from "@/components/ui/tooltip";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { rangeFor, periodLabelFor, type Range } from "@/lib/period";
import { staffApi, paiseToINR, type StaffPerformanceRow } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import {
  isPerformanceAccessDenied,
  parsePerformanceSort,
  performanceEmptyKind,
  performanceTotals,
  withPerformanceSort,
} from "@/lib/staff/performance-view";

const NAVY = "#0C2540";

/** Minutes → "3h 20m" / "45m" / "2d 4h" — a bare minute count is unreadable past an hour. */
function humanMinutes(mins: number | null): string {
  if (mins == null) return "—";
  if (mins < 60) return `${mins}m`;
  if (mins < 60 * 24) {
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m ? `${h}h ${m}m` : `${h}h`;
  }
  const d = Math.floor(mins / (60 * 24));
  const h = Math.floor((mins % (60 * 24)) / 60);
  return h ? `${d}d ${h}h` : `${d}d`;
}

/** Local time-of-day for a first/last action, or an em dash when they did nothing. */
function clockTime(isoTs: string | null): string {
  if (!isoTs) return "—";
  return new Date(isoTs).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export default function StaffPerformancePage() {
  // `useSearchParams` (the persisted sort) needs a Suspense boundary for static prerendering — the
  // same arrangement as `/staff/loans`.
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded bg-grey-100" />}>
      <StaffPerformanceInner />
    </React.Suspense>
  );
}

function StaffPerformanceInner() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  // Read once, as the sort's initial state; the effect below writes it back on every change.
  const initialSort = parsePerformanceSort(searchParams.get("sort"), searchParams.get("dir"));

  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});

  const range: Range = React.useMemo(() => rangeFor(preset, custom), [preset, custom]);

  const q = useQuery({
    queryKey: ["staff-performance", range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.performance(range.from, range.to),
    retry: false,
    // The key is the period alone — the roster is the caller's own, never another identity — so the
    // last window's figures may stay on screen while the next loads, rather than every tile
    // dropping to a fabricated 0 for the length of the request.
    placeholderData: keepPreviousData,
  });

  // An errored query renders an ErrorState in place of the register, so nothing else on the page
  // may keep describing data the page no longer stands behind.
  const data = q.isError ? undefined : q.data;
  const allRows = React.useMemo(() => data?.rows ?? [], [data]);
  const daily = data?.daily ?? [];
  const callTrackingSince = data?.callTrackingSince;

  // Filtered client-side: the roster is a company's staff list (tens of rows), already in memory,
  // so a round-trip per dropdown tick would be slower and no more correct.
  const filterColumns = React.useMemo<Array<FilterColumn<StaffPerformanceRow>>>(
    () => [
      { key: "staffName", value: (r) => r.staffName },
      { key: "role", value: (r) => ROLE_LABEL[r.role] ?? r.role },
    ],
    [],
  );
  const { filtered: rows, selectionFor, optionsFor, setFilter, clearAll, activeCount } =
    useColumnFilters(allRows, filterColumns);

  const { sorted, sortKey, dir, toggle, setSort } = useTableSort<StaffPerformanceRow>(
    rows,
    initialSort.key,
    initialSort.dir,
  );

  // Persist the sort in the URL so a view is linkable and survives a reload. Same convention as
  // `/staff/loans`: defaults are omitted, and the URL is only replaced when it actually changes.
  React.useEffect(() => {
    const current = searchParams.toString();
    const qs = withPerformanceSort(current, sortKey, dir);
    if (qs !== current) router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sortKey, dir]);

  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(sorted);
  const emptyKind = performanceEmptyKind(allRows.length, rows.length);

  const periodLabel = periodLabelFor(preset, custom);

  // Totals follow the column filters, so the tiles always describe the rows actually on screen rather
  // than a hidden population — a filtered table under unfiltered totals reads as a bug. Picking one
  // person in the Staff dropdown is therefore also how you read that person's figures on their own.
  // `null` until there is data: the tiles then render "—", never a 0 nobody measured.
  const totals = performanceTotals(data ? rows : undefined);

  /** True when the window reaches back past the point calls started being attributed. */
  const callsPartial = !!callTrackingSince && (!range.from || range.from < callTrackingSince);
  const callsNote = callTrackingSince
    ? `Telecaller + collections calls. Calls have only been attributed to a person since ${callTrackingSince}; anything earlier isn't counted.`
    : undefined;

  // A refusal here means the caller's role has no roster to show — say so plainly rather than
  // rendering an empty table that looks like "nobody did anything". Any other failure falls through
  // to the normal page, which keeps the period picker and offers a retry in place of the table.
  if (q.isError && isPerformanceAccessDenied(q.error)) {
    return (
      <div>
        <PageHeader title="Staff performance" subtitle="What each employee got through" />
        <NoAccessNotice message={errMessage(q.error)} />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Staff performance"
        subtitle="What each employee got through, off the application-event trail"
      >
        <ExportMenu
          title="Staff performance"
          subtitle={periodLabel}
          fileBase="dhanboost-staff-performance"
          rows={sorted}
          meta={{
            periodLabel,
            from: range.from,
            to: range.to,
            timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          }}
          columns={[
            { header: "Staff", value: (r: StaffPerformanceRow) => r.staffName },
            { header: "Role", value: (r) => ROLE_LABEL[r.role] ?? r.role },
            { header: "Accepted", value: (r) => r.accepted },
            { header: "Rejected", value: (r) => r.rejected },
            { header: "In queue now", value: (r) => r.pendingNow },
            { header: "Total actions", value: (r) => r.totalActions },
            { header: "Active days", value: (r) => r.activeDays },
            { header: "Avg turnaround", value: (r) => humanMinutes(r.avgTurnaroundMinutes) },
            { header: "Value moved", value: (r) => (r.moneyPaise ? paiseToINR(r.moneyPaise) : "—") },
            { header: "Calls", value: (r) => r.callsMade },
            { header: "First action", value: (r) => (r.firstActionAt ? formatDateTime(r.firstActionAt) : "") },
            { header: "Last action", value: (r) => (r.lastActionAt ? formatDateTime(r.lastActionAt) : "") },
          ]}
        />
        <RefreshButton queryKeys={[["staff-performance"]]} />
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <PeriodPicker preset={preset} onPreset={setPreset} custom={custom} onCustom={setCustom} />
        {/* The way out of a filter set on a column that has since been scrolled off — without it the
            only clue that rows are hidden is a caret the reader has to go hunting for. */}
        {activeCount > 0 && data && (
          <button
            type="button"
            onClick={clearAll}
            className="mb-4 flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink"
          >
            <FilterX size={13} /> Clear filters — showing {rows.length} of {allRows.length} staff
          </button>
        )}
      </div>

      {/* The four windowed tiles, then — past a hairline — the one live tile. "In queue now" is a
          snapshot of right now, so sitting in the same row as the period's figures it read as one
          of them; the divider and caption say it does not move with the picker. */}
      <div
        className={`mb-4 flex flex-col gap-3 transition-opacity lg:flex-row ${q.isPlaceholderData ? "opacity-60" : ""}`}
        aria-busy={q.isPlaceholderData}
      >
        <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:flex-[4] lg:grid-cols-4">
          <StatCard label="Approved" value={totals?.accepted ?? null} accent="success" />
          <StatCard label="Rejected" value={totals?.rejected ?? null} accent="error" />
          <StatCard
            label="Total actions"
            value={totals?.actions ?? null}
            info="Every logged action in the period, including routing steps like assignment — not just approvals and rejections."
          />
          <StatCard label="Calls" value={totals?.calls ?? null} info={callsNote} />
        </div>
        <div aria-hidden="true" className="h-px shrink-0 bg-line lg:h-auto lg:w-px" />
        <div className="min-w-0 lg:flex-1">
          <StatCard
            label="In queue now"
            value={totals?.pending ?? null}
            accent="gold"
            hint="Live — not affected by the period"
            info="Files sitting with these staff right now. A live snapshot — it does not change with the selected period."
          />
        </div>
      </div>

      {daily.length > 0 && (
        // Dimmed like the tiles and the register: under keepPreviousData this is still the last
        // window's trend until the new one lands, and must not read as the picked period's.
        <div
          className={`mb-4 rounded border border-line bg-white p-4 shadow-sm transition-opacity ${q.isPlaceholderData ? "opacity-60" : ""}`}
          aria-busy={q.isPlaceholderData}
        >
          {/* The trend is computed server-side across the whole visible roster and the rows carry no
              per-day breakdown to rebuild it from, so it cannot follow a column filter the way the
              tiles above do. Say so rather than let it read as the selected person's own activity. */}
          <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
            Actions per day
            {activeCount > 0 && <span className="normal-case"> — all staff, not filtered</span>}
          </div>
          <div style={{ width: "100%", height: 180 }}>
            <ResponsiveContainer>
              <LineChart data={daily} margin={{ top: 4, right: 8, bottom: 4, left: -20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
                <XAxis dataKey="date" tick={{ fontSize: 10 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <RTooltip />
                <Line type="monotone" dataKey="actions" stroke={NAVY} strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {q.isError ? (
        <div className="rounded border border-line bg-white shadow-sm">
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        </div>
      ) : q.isLoading ? (
        <Skeleton variant="table" rows={8} cols={13} className="rounded border border-line bg-white shadow-sm" />
      ) : (
        // Dimmed while the previous period's rows stand in for the next one's (keepPreviousData), so
        // they are not mistaken for the new window's figures.
        <div
          className={`rounded border border-line bg-white shadow-sm transition-opacity ${q.isPlaceholderData ? "opacity-60" : ""}`}
          aria-busy={q.isPlaceholderData}
        >
          {/* `staff-register-scroll` pins the header; PaginationBar sits after the scroller so it
              does not scroll away with the rows. The offset clears the shell header, PageHeader,
              the period row, the stat-card row and the 180px chart (~46rem) — capped by min() so
              that on a laptop-height viewport the register keeps at least 20rem of rows instead
              of collapsing to its header row. */}
          <div
            className="staff-table-scroll staff-register-scroll"
            style={{ "--register-offset": "min(46rem, calc(100dvh - 20rem))" } as React.CSSProperties}
          >
            <table className="staff-data-table">
              <caption className="sr-only">Staff performance register</caption>
              <thead>
                <tr>
                  <th scope="col">S.No.</th>
                  <FilterableTh
                    label="Staff"
                    sortKey="staffName"
                    active={sortKey}
                    dir={dir}
                    onToggle={toggle}
                    setSort={setSort}
                    options={optionsFor("staffName")}
                    selected={selectionFor("staffName")}
                    onApply={(v) => setFilter("staffName", v)}
                  />
                  <FilterableTh
                    label="Role"
                    sortKey="role"
                    active={sortKey}
                    dir={dir}
                    onToggle={toggle}
                    setSort={setSort}
                    options={optionsFor("role")}
                    selected={selectionFor("role")}
                    onApply={(v) => setFilter("role", v)}
                  />
                  <SortableTh label="Approved" sortKey="accepted" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <SortableTh label="Rejected" sortKey="rejected" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <SortableTh label="In queue now" sortKey="pendingNow" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <SortableTh label="Actions" sortKey="totalActions" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <SortableTh label="Active days" sortKey="activeDays" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <SortableTh label="Avg turnaround" sortKey="avgTurnaroundMinutes" active={sortKey} dir={dir} onToggle={toggle} />
                  <SortableTh label="Value moved" sortKey="moneyPaise" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <SortableTh label="Calls" sortKey="callsMade" active={sortKey} dir={dir} onToggle={toggle} className="num" />
                  <th scope="col">First / last action</th>
                  <th scope="col">
                    <span className="sr-only">Decision history</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {emptyKind === "filtered" ? (
                  <EmptyState
                    title="No staff match the current filters"
                    inTable={13}
                    action={
                      <button
                        type="button"
                        onClick={clearAll}
                        className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink"
                      >
                        <FilterX size={13} /> Clear filters
                      </button>
                    }
                  />
                ) : emptyKind === "no-roster" ? (
                  <EmptyState title="No staff in your scope" inTable={13} />
                ) : (
                  pageRows.map((r, i) => (
                    <tr key={r.staffId}>
                      <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                      <td className="staff-cell">
                        {r.staffName}
                        {!r.active && <span className="ml-1 text-xs text-muted">(inactive)</span>}
                      </td>
                      <td className="staff-cell">{ROLE_LABEL[r.role] ?? r.role}</td>
                      <td className="num font-semibold text-success-700">{r.accepted}</td>
                      <td className="num font-semibold text-error-700">{r.rejected}</td>
                      <td className="num">{r.pendingNow}</td>
                      <td className="num">{r.totalActions}</td>
                      <td className="num">{r.activeDays}</td>
                      <td>
                        {humanMinutes(r.avgTurnaroundMinutes)}
                        {r.avgTurnaroundMinutes == null && r.totalActions > 0 && (
                          <InfoTooltip content="No decision in this period had a matching assignment to measure from, so there is nothing to average — this is not a zero wait." />
                        )}
                      </td>
                      <td className="num font-mono">{r.moneyPaise ? paiseToINR(r.moneyPaise) : "—"}</td>
                      <td className="num">
                        {r.callsMade}
                        {callsPartial && r.callsMade === 0 && callsNote && (
                          <InfoTooltip content={callsNote} />
                        )}
                      </td>
                      <td className="text-muted">
                        {clockTime(r.firstActionAt)} / {clockTime(r.lastActionAt)}
                      </td>
                      <td>
                        {/* Carry the window through, so the totals on the next page match these. */}
                        <Link
                          href={`/staff/my-decisions?staffId=${r.staffId}${range.from ? `&from=${range.from}` : ""}${range.to ? `&to=${range.to}` : ""}`}
                          className="text-xs font-semibold text-navy underline"
                        >
                          Open
                        </Link>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>
      )}
    </div>
  );
}
