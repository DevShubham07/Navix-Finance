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
import { Search } from "lucide-react";
import { PageHeader, StatCard, RefreshButton } from "@/components/staff/staff-ui";
import { ExportMenu } from "@/components/staff/export-menu";
import { NoAccessNotice, errMessage, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { PeriodPicker } from "@/components/staff/period-picker";
import { InfoTooltip } from "@/components/ui/tooltip";
import { Input } from "@/components/ui";
import { rangeFor, periodLabelFor, type Range } from "@/lib/period";
import { staffApi, paiseToINR, type StaffPerformanceRow } from "@/lib/api/applications";

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
  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});

  const [search, setSearch] = React.useState("");

  const range: Range = React.useMemo(() => rangeFor(preset, custom), [preset, custom]);

  const q = useQuery({
    queryKey: ["staff-performance", range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.performance(range.from, range.to),
    retry: false,
  });

  const allRows = React.useMemo(() => q.data?.rows ?? [], [q.data]);
  const daily = q.data?.daily ?? [];
  const callTrackingSince = q.data?.callTrackingSince;

  // Filtered client-side: the roster is a company's staff list (tens of rows), already in memory,
  // so a round-trip per keystroke would be slower and no more correct.
  const rows = React.useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return allRows;
    return allRows.filter((r) =>
      r.staffName.toLowerCase().includes(needle)
      || (ROLE_LABEL[r.role] ?? r.role).toLowerCase().includes(needle));
  }, [allRows, search]);

  const { sorted, sortKey, dir, toggle } = useTableSort<StaffPerformanceRow>(rows, "totalActions", "desc");
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(sorted);

  const periodLabel = periodLabelFor(preset, custom);

  // Totals follow the search, so the tiles always describe the rows actually on screen rather than
  // a hidden population — a filtered table under unfiltered totals reads as a bug.
  const totals = rows.reduce(
    (acc, r) => ({
      accepted: acc.accepted + r.accepted,
      rejected: acc.rejected + r.rejected,
      actions: acc.actions + r.totalActions,
      pending: acc.pending + r.pendingNow,
      calls: acc.calls + r.callsMade,
    }),
    { accepted: 0, rejected: 0, actions: 0, pending: 0, calls: 0 },
  );

  /** True when the window reaches back past the point calls started being attributed. */
  const callsPartial = !!callTrackingSince && (!range.from || range.from < callTrackingSince);
  const callsNote = callTrackingSince
    ? `Telecaller + collections calls. Calls have only been attributed to a person since ${callTrackingSince}; anything earlier isn't counted.`
    : undefined;

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
            { header: "Calls", value: (r) => r.callsMade },
            { header: "First action", value: (r) => r.firstActionAt ?? "" },
            { header: "Last action", value: (r) => r.lastActionAt ?? "" },
          ]}
        />
        <RefreshButton queryKeys={[["staff-performance"]]} />
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <PeriodPicker preset={preset} onPreset={setPreset} custom={custom} onCustom={setCustom} />
        <Input
          aria-label="Search employees"
          placeholder="Search name or role…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          leftIcon={<Search size={15} />}
          className="!mb-4 w-full sm:w-64"
        />
      </div>

      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
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
        <StatCard label="Calls" value={totals.calls} info={callsNote} />
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
                <SortableTh label="Calls" sortKey="callsMade" active={sortKey} dir={dir} onToggle={toggle} />
                <th>First / last action</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {pageRows.length === 0 ? (
                <tr>
                  <td colSpan={13} className="py-8 text-center text-muted">
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
                    <td>
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
