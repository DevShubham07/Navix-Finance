"use client";

import * as React from "react";
import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";
import { Download, Search, X } from "lucide-react";
import {
  Drawer,
  DrawerBody,
  DrawerHeader,
  DrawerTitle,
  EmptyState,
  ErrorState,
  Skeleton,
  StatusBadge,
} from "@/components/ui";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import {
  dashboardApi,
  paiseToINR,
  type DashMetric,
  type DashParams,
  type DashRecordRow,
  type DashSegment,
} from "@/lib/api/applications";
import { useStaffSession } from "@/lib/auth/staff-session";
import { cn } from "@/lib/utils";
import { SEGMENT_CHIP, SEGMENT_PILL } from "./colors";
import { fmtDay, nf } from "./fmt";

const PAGE = 50;
const EXPORT_PAGE = 500;

/** What a click on a number opens: the metric, an optional row key, and the heading to show. */
export interface RecordsTarget {
  metric: DashMetric;
  key?: string;
  title: string;
  /** Overrides the drawer's period (a clicked month, or an AUM as-of date passed as `to`). */
  range?: { from?: string; to?: string };
  /** Opens on this segment (e.g. the "New" link of a table opens Fresh). */
  segment?: DashSegment;
}

export interface RecordsDrawerProps {
  target: RecordsTarget | null;
  onClose: () => void;
  /** view / from / to / staffIds the clicked number was computed under. */
  params: DashParams;
  /** Shown under the title, e.g. "01 Oct - 09 Oct 2026". */
  periodLabel?: string;
}

const SEGMENTS: { key: DashSegment; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "FRESH", label: "Fresh" },
  { key: "RELOAN", label: "Re-loan" },
];

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = React.useState(value);
  React.useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/**
 * The records behind any dashboard number. The total line is the backend's `total` for the same
 * metric fragment the clicked figure was counted with, so the two always agree.
 */
export function RecordsDrawer({ target, onClose, params, periodLabel }: RecordsDrawerProps) {
  const [segment, setSegment] = React.useState<DashSegment>("ALL");
  const [search, setSearch] = React.useState("");
  const q = useDebounced(search.trim(), 300);
  const [detail, setDetail] = React.useState<DashRecordRow | null>(null);
  const { session } = useStaffSession();
  const [exporting, setExporting] = React.useState(false);

  // A new target starts clean.
  const targetId = target
    ? `${target.metric}|${target.key ?? ""}|${target.range?.from ?? ""}|${target.range?.to ?? ""}|${target.segment ?? ""}`
    : "";
  React.useEffect(() => {
    setSegment(target?.segment ?? "ALL");
    setSearch("");
    setDetail(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the target identity changes
  }, [targetId]);

  // The period the number was counted under, unless the click pinned its own (a month, an as-of date).
  const eff: DashParams = {
    ...params,
    from: target?.range?.from ?? params.from,
    to: target?.range?.to ?? params.to,
  };
  const staffKey = params.staffIds?.join(",") ?? "";
  const query = useInfiniteQuery({
    queryKey: [
      "staff-dashboard-records",
      eff.view,
      eff.from ?? "",
      eff.to ?? "",
      staffKey,
      target?.metric,
      target?.key ?? "",
      segment,
      q,
    ],
    queryFn: ({ pageParam }) =>
      dashboardApi.records({
        ...eff,
        metric: target!.metric,
        key: target?.key,
        segment,
        q: q || undefined,
        page: pageParam,
        size: PAGE,
      }),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => {
      const loaded = pages.reduce((n, p) => n + p.rows.length, 0);
      return last.rows.length > 0 && loaded < last.total ? pages.length : undefined;
    },
    enabled: target != null,
    placeholderData: keepPreviousData,
  });

  const first = query.data?.pages[0];
  const rows = React.useMemo(() => query.data?.pages.flatMap((p) => p.rows) ?? [], [query.data]);
  // Remember the unfiltered split so the pills do not flicker to 0 while a segment refetches.
  const counts = React.useRef({ fresh: 0, reloan: 0 });
  if (first) counts.current = { fresh: first.freshCount, reloan: first.reloanCount };
  const pillCount = (s: DashSegment) =>
    s === "ALL"
      ? counts.current.fresh + counts.current.reloan
      : s === "FRESH"
        ? counts.current.fresh
        : counts.current.reloan;

  const doExport = async () => {
    if (!target) return;
    setExporting(true);
    try {
      const all: DashRecordRow[] = [];
      for (let page = 0; ; page++) {
        const r = await dashboardApi.records({
          ...eff,
          metric: target.metric,
          key: target.key,
          segment,
          q: q || undefined,
          page,
          size: EXPORT_PAGE,
        });
        all.push(...r.rows);
        if (r.rows.length === 0 || all.length >= r.total) break;
      }
      const { exportCsv } = await import("@/lib/export/exporters");
      exportCsv(
        `dashboard-${target.metric.toLowerCase()}`,
        [
          { header: "Application", value: (r: DashRecordRow) => r.applicationId },
          { header: "Loan", value: (r: DashRecordRow) => r.loanId },
          { header: "Customer", value: (r: DashRecordRow) => r.customerName },
          { header: "Mobile (last 4)", value: (r: DashRecordRow) => r.mobileLast4 },
          { header: "Status", value: (r: DashRecordRow) => r.status },
          { header: "Segment", value: (r: DashRecordRow) => r.segment },
          { header: "Amount (INR)", value: (r: DashRecordRow) => (r.amountPaise == null ? "" : r.amountPaise / 100) },
          { header: "Owed (INR)", value: (r: DashRecordRow) => (r.owedPaise == null ? "" : r.owedPaise / 100) },
          { header: "Disbursed on", value: (r: DashRecordRow) => r.disbursedOn },
          { header: "Due date", value: (r: DashRecordRow) => r.dueDate },
          { header: "Closed on", value: (r: DashRecordRow) => r.closedOn },
          { header: "Assignee", value: (r: DashRecordRow) => r.assigneeName },
          { header: "State", value: (r: DashRecordRow) => r.state },
        ],
        all,
      );
    } finally {
      setExporting(false);
    }
  };

  const open = target != null;
  const total = first?.total ?? 0;

  return (
    <>
      <Drawer
        open={open}
        onClose={() => {
          // Esc inside the stacked detail dialog must not also tear down the drawer under it.
          if (detail == null) onClose();
        }}
        aria-labelledby="records-drawer-title"
        className="sm:max-w-2xl lg:max-w-4xl"
      >
        <DrawerHeader>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <DrawerTitle id="records-drawer-title">{target?.title ?? "Records"}</DrawerTitle>
              {periodLabel && <p className="m-0 text-xs text-muted">{periodLabel}</p>}
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close records"
              className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
            >
              <X size={18} />
            </button>
          </div>

          <div className="mt-2 flex flex-wrap items-center gap-2">
            <div role="group" aria-label="Segment" className="flex gap-1 rounded-full bg-grey-100 p-1">
              {SEGMENTS.map((s) => (
                <button
                  key={s.key}
                  type="button"
                  aria-pressed={segment === s.key}
                  onClick={() => setSegment(s.key)}
                  className={cn(
                    "rounded-full px-3 py-1 text-xs font-semibold transition-colors",
                    segment === s.key ? "text-white" : "text-muted hover:text-ink",
                  )}
                  style={segment === s.key ? { background: SEGMENT_PILL[s.key] } : undefined}
                >
                  {s.label} {nf(pillCount(s.key))}
                </button>
              ))}
            </div>
            <label className="relative ml-auto flex min-w-[10rem] flex-1 items-center sm:max-w-xs">
              <Search size={13} aria-hidden className="absolute left-2 text-muted" />
              <input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search name or application #"
                aria-label="Search records"
                className="w-full rounded border border-line py-1.5 pl-7 pr-2 text-xs"
              />
            </label>
            {session?.realRole === "ADMIN" && (
              <button
                type="button"
                onClick={() => void doExport()}
                disabled={exporting || total === 0}
                className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-navy hover:bg-grey-100 disabled:opacity-50"
              >
                <Download size={13} /> {exporting ? "Preparing…" : "Export CSV"}
              </button>
            )}
          </div>

          <p
            className="m-0 mt-2 rounded bg-navy-tint px-3 py-1.5 text-xs font-semibold text-navy"
            data-testid="records-total"
          >
            {query.isLoading
              ? "Loading…"
              : `${nf(total)} ${total === 1 ? "record" : "records"} · ${paiseToINR(first?.sumPaise)}`}
          </p>
        </DrawerHeader>

        <DrawerBody className="p-0">
          {query.isError ? (
            <ErrorState error={query.error} onRetry={() => void query.refetch()} />
          ) : query.isLoading ? (
            <div className="p-4">
              <Skeleton variant="table" rows={8} cols={6} />
            </div>
          ) : rows.length === 0 ? (
            <EmptyState title="No records match" hint="Try another segment or clear the search." />
          ) : (
            <>
              <div className="staff-table-scroll">
                <table className="w-full min-w-[40rem] text-left text-xs">
                  <thead className="sticky top-0 bg-navy text-white">
                    <tr>
                      {["App #", "Customer", "Status", "Amount", "Dates", "Assignee", "State"].map((h) => (
                        <th key={h} className="px-3 py-2 font-semibold">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r, i) => (
                      <tr
                        key={`${r.applicationId ?? "x"}-${r.loanId ?? "x"}-${i}`}
                        tabIndex={0}
                        onClick={() => setDetail(r)}
                        onKeyDown={(e) => e.key === "Enter" && setDetail(r)}
                        className="cursor-pointer border-b border-line hover:bg-grey-50 focus:bg-grey-50 focus:outline-none"
                      >
                        <td className="px-3 py-2 font-semibold text-navy">{r.applicationId ?? "—"}</td>
                        <td className="px-3 py-2">
                          <span className="block font-medium text-ink">{r.customerName ?? "—"}</span>
                          <span className="text-muted">
                            {r.mobileLast4 ? `••••${r.mobileLast4}` : ""}{" "}
                            <span
                              className="rounded-full px-1.5 py-0.5 text-[10px] font-semibold"
                              style={SEGMENT_CHIP[r.segment]}
                            >
                              {r.segment === "RELOAN" ? "Re-loan" : "Fresh"}
                            </span>
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <StatusBadge kind="application" value={r.status} />
                        </td>
                        <td className="px-3 py-2 tabular-nums">
                          {paiseToINR(r.amountPaise)}
                          {r.owedPaise != null && (
                            <span className="block text-muted">owed {paiseToINR(r.owedPaise)}</span>
                          )}
                        </td>
                        <td className="px-3 py-2 text-muted">
                          {r.disbursedOn && <span className="block">Disb {fmtDay(r.disbursedOn)}</span>}
                          {r.dueDate && <span className="block">Due {fmtDay(r.dueDate)}</span>}
                          {r.closedOn && <span className="block">Closed {fmtDay(r.closedOn)}</span>}
                          {!r.disbursedOn && !r.dueDate && !r.closedOn && "—"}
                        </td>
                        <td className="px-3 py-2">{r.assigneeName ?? "—"}</td>
                        <td className="px-3 py-2">{r.state ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {query.hasNextPage && (
                <div className="flex justify-center p-3">
                  <button
                    type="button"
                    onClick={() => void query.fetchNextPage()}
                    disabled={query.isFetchingNextPage}
                    className="btn btn-sm btn-outline"
                  >
                    {query.isFetchingNextPage ? "Loading…" : `Load more (${nf(rows.length)} of ${nf(total)})`}
                  </button>
                </div>
              )}
            </>
          )}
        </DrawerBody>
      </Drawer>

      {detail && (detail.applicationId != null || detail.loanId != null) && (
        <ApplicationDetailDialog
          applicationId={detail.applicationId}
          loanId={detail.applicationId == null ? detail.loanId : null}
          onClose={() => setDetail(null)}
        />
      )}
    </>
  );
}

/** Convenience state for a page/tab: `const { target, open, close } = useRecordsTarget()`. */
export function useRecordsTarget() {
  const [target, setTarget] = React.useState<RecordsTarget | null>(null);
  const open = React.useCallback((t: RecordsTarget) => setTarget(t), []);
  const close = React.useCallback(() => setTarget(null), []);
  return { target, open, close };
}
