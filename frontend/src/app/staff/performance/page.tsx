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
import { useQuery } from "@tanstack/react-query";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip as RTooltip,
  XAxis,
  YAxis,
} from "recharts";
import { PageHeader, StatCard, RefreshButton } from "@/components/staff/staff-ui";
import { ExportMenu } from "@/components/staff/export-menu";
import { NoAccessNotice, errMessage, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { InfoTooltip } from "@/components/ui/tooltip";
import { staffApi, paiseToINR, type StaffPerformanceRow } from "@/lib/api/applications";

const NAVY = "#0C2540";

/** Local-time ISO yyyy-mm-dd — never UTC, which shifts the day at either end of the window. */
function iso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function addDays(d: Date, days: number): Date {
  const copy = new Date(d);
  copy.setDate(copy.getDate() + days);
  return copy;
}

/** Monday of the week containing `d` (ISO week start, the Indian business convention). */
function mondayOf(d: Date): Date {
  // getDay(): 0=Sun..6=Sat. Sunday belongs to the week that started six days earlier, not the next one.
  return addDays(d, -((d.getDay() + 6) % 7));
}

type Range = { from?: string; to?: string };

/**
 * Bounded windows, not rolling ones: "Last week" must END last Sunday, otherwise it overlaps this
 * week and the two periods double-count the same actions.
 */
const PRESETS: { key: string; label: string; range: () => Range }[] = [
  { key: "today", label: "Today", range: () => ({ from: iso(new Date()), to: iso(new Date()) }) },
  {
    key: "yesterday",
    label: "Yesterday",
    range: () => ({ from: iso(addDays(new Date(), -1)), to: iso(addDays(new Date(), -1)) }),
  },
  {
    key: "this-week",
    label: "This week",
    range: () => ({ from: iso(mondayOf(new Date())), to: iso(new Date()) }),
  },
  {
    key: "last-week",
    label: "Last week",
    range: () => {
      const lastMonday = addDays(mondayOf(new Date()), -7);
      return { from: iso(lastMonday), to: iso(addDays(lastMonday, 6)) };
    },
  },
  {
    key: "this-month",
    label: "This month",
    range: () => {
      const now = new Date();
      return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(now) };
    },
  },
  {
    key: "last-month",
    label: "Last month",
    range: () => {
      const now = new Date();
      return {
        from: iso(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
        to: iso(new Date(now.getFullYear(), now.getMonth(), 0)),
      };
    },
  },
  { key: "all", label: "All time", range: () => ({}) },
];

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
  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});

  const range: Range = React.useMemo(() => {
    if (preset === "custom") return custom;
    return PRESETS.find((p) => p.key === preset)?.range() ?? {};
  }, [preset, custom]);

  const q = useQuery({
    queryKey: ["staff-performance", range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.performance(range.from, range.to),
    retry: false,
  });

  const rows = React.useMemo(() => q.data?.rows ?? [], [q.data]);
  const daily = q.data?.daily ?? [];
  const { sorted, sortKey, dir, toggle } = useTableSort<StaffPerformanceRow>(rows, "totalActions", "desc");
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(sorted);

  const periodLabel = preset === "custom"
    ? `${custom.from ?? "start"} → ${custom.to ?? "today"}`
    : PRESETS.find((p) => p.key === preset)?.label ?? "";

  const totals = rows.reduce(
    (acc, r) => ({
      accepted: acc.accepted + r.accepted,
      rejected: acc.rejected + r.rejected,
      actions: acc.actions + r.totalActions,
      pending: acc.pending + r.pendingNow,
    }),
    { accepted: 0, rejected: 0, actions: 0, pending: 0 },
  );

  // A 403 here means the caller's role has no roster to show — say so plainly rather than
  // rendering an empty table that looks like "nobody did anything".
  if (q.isError) {
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
            { header: "First action", value: (r) => r.firstActionAt ?? "" },
            { header: "Last action", value: (r) => r.lastActionAt ?? "" },
          ]}
        />
        <RefreshButton queryKeys={[["staff-performance"]]} />
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-muted">Period</span>
        <div className="flex flex-wrap items-center gap-1 rounded-full border border-line bg-white p-1">
          {PRESETS.map((p) => (
            <button
              key={p.key}
              onClick={() => setPreset(p.key)}
              className={`rounded-full px-3 py-1 text-sm font-semibold transition ${
                preset === p.key ? "bg-gold text-white" : "text-muted hover:text-navy"
              }`}
            >
              {p.label}
            </button>
          ))}
          <button
            onClick={() => setPreset("custom")}
            className={`rounded-full px-3 py-1 text-sm font-semibold transition ${
              preset === "custom" ? "bg-gold text-white" : "text-muted hover:text-navy"
            }`}
          >
            Custom
          </button>
        </div>
        {preset === "custom" && (
          <div className="flex items-center gap-2">
            <input
              type="date"
              aria-label="From"
              value={custom.from ?? ""}
              onChange={(e) => setCustom((c) => ({ ...c, from: e.target.value || undefined }))}
              className="rounded border border-line px-2 py-1 text-sm"
            />
            <span className="text-xs text-muted">→</span>
            <input
              type="date"
              aria-label="To"
              value={custom.to ?? ""}
              onChange={(e) => setCustom((c) => ({ ...c, to: e.target.value || undefined }))}
              className="rounded border border-line px-2 py-1 text-sm"
            />
          </div>
        )}
      </div>

      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Approved" value={totals.accepted} accent="success" />
        <StatCard label="Rejected" value={totals.rejected} accent="error" />
        <StatCard
          label="Total actions"
          value={totals.actions}
          info="Every logged action in the period, including routing steps like assignment — not just approvals and rejections."
        />
        <StatCard
          label="In queue now"
          value={totals.pending}
          accent="gold"
          info="Files sitting with these staff right now. A live snapshot — it does not change with the selected period."
        />
      </div>

      {daily.length > 0 && (
        <div className="mb-4 rounded border border-line bg-white p-4 shadow-sm">
          <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
            Actions per day
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

      {q.isLoading ? (
        <div className="h-48 animate-pulse rounded border border-line bg-white" />
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>S.No.</th>
                <SortableTh label="Staff" sortKey="staffName" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Role" sortKey="role" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Approved" sortKey="accepted" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Rejected" sortKey="rejected" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="In queue now" sortKey="pendingNow" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Actions" sortKey="totalActions" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Active days" sortKey="activeDays" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Avg turnaround" sortKey="avgTurnaroundMinutes" active={sortKey} dir={dir} onToggle={toggle} />
                <SortableTh label="Value moved" sortKey="moneyPaise" active={sortKey} dir={dir} onToggle={toggle} />
                <th>First / last action</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {pageRows.length === 0 ? (
                <tr>
                  <td colSpan={12} className="py-8 text-center text-muted">
                    No staff activity in this period.
                  </td>
                </tr>
              ) : (
                pageRows.map((r, i) => (
                  <tr key={r.staffId}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td className="staff-cell">
                      {r.staffName}
                      {!r.active && <span className="ml-1 text-xs text-muted">(inactive)</span>}
                    </td>
                    <td className="staff-cell">{ROLE_LABEL[r.role] ?? r.role}</td>
                    <td className="font-semibold text-success-700">{r.accepted}</td>
                    <td className="font-semibold text-error-700">{r.rejected}</td>
                    <td>{r.pendingNow}</td>
                    <td>{r.totalActions}</td>
                    <td>{r.activeDays}</td>
                    <td>
                      {humanMinutes(r.avgTurnaroundMinutes)}
                      {r.avgTurnaroundMinutes == null && r.totalActions > 0 && (
                        <InfoTooltip content="No decision in this period had a matching assignment to measure from, so there is nothing to average — this is not a zero wait." />
                      )}
                    </td>
                    <td className="font-mono">{r.moneyPaise ? paiseToINR(r.moneyPaise) : "—"}</td>
                    <td className="text-muted">
                      {clockTime(r.firstActionAt)} / {clockTime(r.lastActionAt)}
                    </td>
                    <td>
                      <Link
                        href={`/staff/my-decisions?staffId=${r.staffId}`}
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
