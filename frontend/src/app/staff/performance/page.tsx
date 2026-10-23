"use client";

/**
 * Staff performance — what each employee actually got through over a period, measured on the
 * parameters of their own job.
 *
 * Sibling of `/staff/my-decisions`, not under `/staff/admin/*`: Heads can open it too, and the
 * server decides the roster (ADMIN → the company, a Head → their team, anyone else → themselves).
 * Following my-decisions, the nav entry carries no permission for that reason — the data, not the
 * route, is what's scoped.
 *
 * The switcher at the top picks the entity (Credit Executive, Credit Head, Collection Executive,
 * Collection Head, …): each has its own tiles and table columns (`lib/staff/performance-entities.ts`),
 * because one set of credit columns made every collections staffer look idle. Clicking a row opens
 * that person's pop-up, which explains each figure; it links on to `/staff/my-decisions` for the
 * per-file list.
 */

import * as React from "react";
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
import { StaffPerformanceDialog } from "@/components/staff/performance/staff-performance-dialog";
import { InfoTooltip } from "@/components/ui/tooltip";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { rangeFor, periodLabelFor, type Range } from "@/lib/period";
import { staffApi, type StaffPerformanceRow } from "@/lib/api/applications";
import { cn, formatDateTime } from "@/lib/utils";
import {
  isPerformanceAccessDenied,
  parsePerformanceSort,
  performanceEmptyKind,
  withPerformanceEntity,
  withPerformanceSort,
} from "@/lib/staff/performance-view";
import {
  availableEntities,
  dailyFor,
  entityFor,
  formatMetric,
  metricTotal,
  resolveEntity,
  rowsForEntity,
  toViewRows,
  type Metric,
  type PerformanceEntity,
  type PerformanceViewRow,
} from "@/lib/staff/performance-entities";

const NAVY = "#0C2540";

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

  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});
  const [requestedEntity, setRequestedEntity] = React.useState<string | null>(searchParams.get("entity"));

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
  const options = React.useMemo(() => availableEntities(allRows), [allRows]);
  // Until the roster arrives (or when it is empty) there is no entity to pick; the overview stands
  // in so the period picker and the em-dash tiles still render.
  const entity = resolveEntity(requestedEntity, allRows) ?? entityFor("ALL")!;

  const pickEntity = (key: string) => {
    setRequestedEntity(key);
    const current = searchParams.toString();
    const qs = withPerformanceEntity(current, key);
    if (qs !== current) router.replace(`${pathname}?${qs}`, { scroll: false });
  };

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
      <PageHeader title="Staff performance" subtitle="What each employee got through, measured on their own role">
        <RefreshButton queryKeys={[["staff-performance"]]} />
      </PageHeader>

      {options.length > 0 && (
        <div className="mb-4">
          <div
            role="tablist"
            aria-label="Which staff to measure"
            className="flex flex-wrap gap-2 rounded border border-line bg-white p-2 shadow-sm"
          >
            {options.map(({ entity: e, count }) => {
              const on = e.key === entity.key;
              return (
                <button
                  key={e.key}
                  type="button"
                  role="tab"
                  aria-selected={on}
                  onClick={() => pickEntity(e.key)}
                  className={cn(
                    "flex items-center gap-2 rounded px-3 py-2 text-sm font-semibold transition",
                    on ? "bg-navy text-white shadow-sm" : "text-muted hover:bg-grey-100 hover:text-ink",
                  )}
                >
                  {e.label}
                  <span
                    className={cn(
                      "rounded-full px-1.5 text-[11px] tabular-nums",
                      on ? "bg-white/20 text-white" : "bg-grey-100 text-muted",
                    )}
                  >
                    {count}
                  </span>
                </button>
              );
            })}
          </div>
          <p className="mt-2 text-xs text-muted">{entity.blurb}</p>
        </div>
      )}

      {/* Keyed on the entity: each has its own columns, so its sort and column filters start fresh. */}
      <EntityView
        key={entity.key}
        entity={entity}
        pinnedEntity={requestedEntity}
        rows={data ? rowsForEntity(allRows, entity) : undefined}
        callTrackingSince={data?.callTrackingSince}
        range={range}
        preset={preset}
        setPreset={setPreset}
        custom={custom}
        setCustom={setCustom}
        isError={q.isError}
        error={q.error}
        retry={() => void q.refetch()}
        isLoading={q.isLoading}
        isPlaceholder={q.isPlaceholderData}
      />
    </div>
  );
}

function EntityView({
  entity,
  pinnedEntity,
  rows: entityRows,
  callTrackingSince,
  range,
  preset,
  setPreset,
  custom,
  setCustom,
  isError,
  error,
  retry,
  isLoading,
  isPlaceholder,
}: {
  entity: PerformanceEntity;
  /** The entity the user picked (or the URL named), kept in the URL when the sort is written back. */
  pinnedEntity: string | null;
  /** This entity's slice of the roster; undefined until there is data. */
  rows: StaffPerformanceRow[] | undefined;
  callTrackingSince: string | undefined;
  range: Range;
  preset: string;
  setPreset: (p: string) => void;
  custom: Range;
  setCustom: (r: Range) => void;
  isError: boolean;
  error: unknown;
  retry: () => void;
  isLoading: boolean;
  isPlaceholder: boolean;
}) {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  // Read once, as the sort's initial state; the effect below writes it back on every change.
  const initialSort = parsePerformanceSort(searchParams.get("sort"), searchParams.get("dir"), entity);

  const allRows = React.useMemo(() => toViewRows(entityRows ?? [], entity), [entityRows, entity]);
  const showRole = entity.roles.length !== 1;

  // Filtered client-side: the roster is a company's staff list (tens of rows), already in memory,
  // so a round-trip per dropdown tick would be slower and no more correct.
  const filterColumns = React.useMemo<Array<FilterColumn<PerformanceViewRow>>>(
    () => [
      { key: "staffName", value: (r) => r.staffName },
      // `raw` is the stored enum: the dropdown shows "Credit Executive", the filter carries
      // CREDIT_EXECUTIVE, which is the only form a server-side filter could ever accept.
      { key: "role", value: (r) => ROLE_LABEL[r.role] ?? r.role, raw: (r) => r.role },
    ],
    [],
  );
  const { filtered: rows, filterFor, labelsFor, optionsFor, setFilter, clearAll, activeCount } =
    useColumnFilters(allRows, filterColumns);

  const { sorted, sortKey, dir, toggle, setSort } = useTableSort<PerformanceViewRow>(
    rows,
    initialSort.key,
    initialSort.dir,
  );

  // Persist the sort in the URL so a view is linkable and survives a reload. Same convention as
  // `/staff/loans`: defaults are omitted, and the URL is only replaced when it actually changes.
  React.useEffect(() => {
    const current = searchParams.toString();
    // Re-pin the entity: on a switch this runs on mount, possibly before the parent's own replace
    // has reached `searchParams`, and must not write a query string that has lost it.
    const base = new URLSearchParams(current);
    if (pinnedEntity) base.set("entity", pinnedEntity);
    const qs = withPerformanceSort(base.toString(), sortKey, dir, entity);
    if (qs !== current) router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sortKey, dir]);

  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(sorted);
  const emptyKind = performanceEmptyKind(allRows.length, rows.length);
  const [opened, setOpened] = React.useState<StaffPerformanceRow | null>(null);

  const periodLabel = periodLabelFor(preset, custom);

  // Tiles and the trend follow the column filters, so they always describe the rows actually on
  // screen rather than a hidden population — a filtered table under unfiltered totals reads as a bug.
  // `undefined` until there is data: the tiles then render "—", never a 0 nobody measured.
  const visibleRaw = entityRows ? rows.map((r) => r.raw) : undefined;
  const daily = dailyFor(visibleRaw ?? []);
  const metricByKey = new Map(entity.metrics.map((m) => [m.key, m]));
  const tileMetrics = entity.tiles.map((k) => metricByKey.get(k)).filter((m): m is Metric => !!m);
  const windowTiles = tileMetrics.filter((m) => !m.live);
  const liveTiles = tileMetrics.filter((m) => m.live);

  /** True when the window reaches back past the point calls started being attributed. */
  const callsPartial = !!callTrackingSince && (!range.from || range.from < callTrackingSince);
  const callsNote = callTrackingSince
    ? `Calls have only been attributed to a person since ${callTrackingSince}; anything earlier isn't counted.`
    : undefined;
  const infoFor = (m: Metric) => (m.calls && callsNote ? `${m.info} ${callsNote}` : m.info);

  const colCount = entity.metrics.length + (showRole ? 4 : 3) + 1;
  const busy = isPlaceholder ? "opacity-60" : "";

  return (
    <>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <PeriodPicker preset={preset} onPreset={setPreset} custom={custom} onCustom={setCustom} />
        <div className="mb-4 flex flex-wrap items-center gap-2">
          {/* The way out of a filter set on a column that has since been scrolled off — without it the
              only clue that rows are hidden is a caret the reader has to go hunting for. */}
          {activeCount > 0 && entityRows && (
            <button
              type="button"
              onClick={clearAll}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink"
            >
              <FilterX size={13} /> Clear filters — showing {rows.length} of {allRows.length} staff
            </button>
          )}
          <ExportMenu
            title={`Staff performance — ${entity.label}`}
            subtitle={periodLabel}
            fileBase={`dhanboost-staff-performance-${entity.key.toLowerCase().replace(/_/g, "-")}`}
            rows={sorted}
            meta={{
              periodLabel,
              from: range.from,
              to: range.to,
              timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
            }}
            columns={[
              { header: "Staff", value: (r: PerformanceViewRow) => r.staffName },
              { header: "Role", value: (r: PerformanceViewRow) => ROLE_LABEL[r.role] ?? r.role },
              ...entity.metrics.map((m) => ({
                header: m.label,
                value: (r: PerformanceViewRow) => formatMetric(m.fmt, r[m.key] as number | null),
              })),
              { header: "First action", value: (r: PerformanceViewRow) => (r.firstActionAt ? formatDateTime(r.firstActionAt) : "") },
              { header: "Last action", value: (r: PerformanceViewRow) => (r.lastActionAt ? formatDateTime(r.lastActionAt) : "") },
            ]}
          />
        </div>
      </div>

      {/* The windowed tiles, then — past a hairline — the live ones. A live count is a snapshot of
          right now, so sitting in the same row as the period's figures it read as one of them; the
          divider and caption say it does not move with the picker. */}
      <div className={`mb-4 flex flex-col gap-3 transition-opacity lg:flex-row ${busy}`} aria-busy={isPlaceholder}>
        <div
          className={cn(
            "grid min-w-0 grid-cols-2 gap-3 lg:flex-[4]",
            windowTiles.length >= 5 ? "lg:grid-cols-5" : windowTiles.length === 4 ? "lg:grid-cols-4" : "lg:grid-cols-3",
          )}
        >
          {windowTiles.map((m) => (
            <StatCard
              key={m.key}
              label={m.label}
              value={visibleRaw ? formatMetric(m.fmt, metricTotal(m, visibleRaw)) : null}
              accent={m.tone === "success" ? "success" : m.tone === "error" ? "error" : undefined}
              info={infoFor(m)}
            />
          ))}
        </div>
        {liveTiles.length > 0 && (
          <>
            <div aria-hidden="true" className="h-px shrink-0 bg-line lg:h-auto lg:w-px" />
            <div className="grid min-w-0 gap-3 lg:flex-1">
              {liveTiles.map((m) => (
                <StatCard
                  key={m.key}
                  label={m.label}
                  value={visibleRaw ? formatMetric(m.fmt, metricTotal(m, visibleRaw)) : null}
                  accent="gold"
                  hint="Live — not affected by the period"
                  info={m.info}
                />
              ))}
            </div>
          </>
        )}
      </div>

      {daily.length > 0 && (
        // Dimmed like the tiles and the register: under keepPreviousData this is still the last
        // window's trend until the new one lands, and must not read as the picked period's.
        <div className={`mb-4 rounded border border-line bg-white p-4 shadow-sm transition-opacity ${busy}`} aria-busy={isPlaceholder}>
          <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
            Activity per day
            <span className="normal-case">
              {" "}
              — {activeCount > 0 ? `${rows.length} filtered` : entity.key === "ALL" ? "all staff" : entity.label}
            </span>
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

      {isError ? (
        <div className="rounded border border-line bg-white shadow-sm">
          <ErrorState error={error} onRetry={retry} />
        </div>
      ) : isLoading ? (
        <Skeleton variant="table" rows={8} cols={colCount} className="rounded border border-line bg-white shadow-sm" />
      ) : (
        // Dimmed while the previous period's rows stand in for the next one's (keepPreviousData), so
        // they are not mistaken for the new window's figures.
        <div className={`rounded border border-line bg-white shadow-sm transition-opacity ${busy}`} aria-busy={isPlaceholder}>
          {/* `staff-register-scroll` pins the header; PaginationBar sits after the scroller so it
              does not scroll away with the rows. The offset clears the shell header, PageHeader,
              the switcher, the period row, the stat-card row and the 180px chart (~52rem) — capped
              by min() so that on a laptop-height viewport the register keeps at least 20rem of rows
              instead of collapsing to its header row. */}
          <div
            className="staff-table-scroll staff-register-scroll"
            style={{ "--register-offset": "min(52rem, calc(100dvh - 20rem))" } as React.CSSProperties}
          >
            <table className="staff-data-table">
              <caption className="sr-only">Staff performance register — {entity.label}</caption>
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
                    labels={labelsFor("staffName")}
                    filter={filterFor("staffName")}
                    onApply={(v) => setFilter("staffName", v)}
                  />
                  {showRole && (
                    <FilterableTh
                      label="Role"
                      sortKey="role"
                      active={sortKey}
                      dir={dir}
                      onToggle={toggle}
                      setSort={setSort}
                      options={optionsFor("role")}
                      labels={labelsFor("role")}
                      filter={filterFor("role")}
                      onApply={(v) => setFilter("role", v)}
                    />
                  )}
                  {entity.metrics.map((m) => (
                    <SortableTh
                      key={m.key}
                      label={m.label}
                      sortKey={m.key}
                      active={sortKey}
                      dir={dir}
                      onToggle={toggle}
                      className={m.fmt === "minutes" ? undefined : "num"}
                    />
                  ))}
                  <th scope="col">First / last action</th>
                  <th scope="col">
                    <span className="sr-only">Details</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {emptyKind === "filtered" ? (
                  <EmptyState
                    title="No staff match the current filters"
                    inTable={colCount}
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
                  <EmptyState title="No staff in your scope" inTable={colCount} />
                ) : (
                  pageRows.map((r, i) => (
                    <tr
                      key={r.staffId}
                      onClick={() => setOpened(r.raw)}
                      className="cursor-pointer hover:bg-grey-50"
                    >
                      <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                      <td className="staff-cell">
                        {r.staffName}
                        {!r.active && <span className="ml-1 text-xs text-muted">(inactive)</span>}
                      </td>
                      {showRole && <td className="staff-cell">{ROLE_LABEL[r.role] ?? r.role}</td>}
                      {entity.metrics.map((m) => {
                        const v = r[m.key] as number | null;
                        return (
                          <td
                            key={m.key}
                            className={cn(
                              m.fmt !== "minutes" && "num",
                              m.fmt === "money" && "font-mono",
                              m.tone === "success" && "font-semibold text-success-700",
                              m.tone === "error" && "font-semibold text-error-700",
                            )}
                          >
                            {formatMetric(m.fmt, v)}
                            {m.fmt === "minutes" && v == null && r.raw.totalActions > 0 && (
                              <InfoTooltip content="No decision in this period had a matching assignment to measure from, so there is nothing to average — this is not a zero wait." />
                            )}
                            {m.calls && callsPartial && v === 0 && callsNote && <InfoTooltip content={callsNote} />}
                          </td>
                        );
                      })}
                      <td className="text-muted">
                        {clockTime(r.firstActionAt)} / {clockTime(r.lastActionAt)}
                      </td>
                      <td>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setOpened(r.raw);
                          }}
                          className="text-xs font-semibold text-navy underline"
                          aria-label={`View ${r.staffName}'s performance`}
                        >
                          View
                        </button>
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

      {opened && (
        <StaffPerformanceDialog
          row={opened}
          periodLabel={periodLabel}
          from={range.from}
          to={range.to}
          callsNote={callsPartial ? callsNote : undefined}
          onClose={() => setOpened(null)}
        />
      )}
    </>
  );
}
