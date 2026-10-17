"use client";

/**
 * The Collection Head's desk — two panels above the DPD register on `/staff/collections`, for the
 * COLLECTION_HEAD working role and ADMIN (`collections:manage`, the same gate as the assign action).
 *
 *  - **Needs assignment** — every live loan in the worklist with no officer, most overdue first then
 *    due soonest, each with the register's own inline officer picker. The register is paged
 *    server-side, so this is a small dedicated read of the same worklist under the `officerName =
 *    __NULL__` column filter the register itself uses for "Unallocated" — not a second copy of the
 *    data model. It lives under the `["collections-worklist"]` key so an assign anywhere (this panel,
 *    the register cell, the bulk dialog) refreshes it and the row leaves.
 *  - **Team today** — one row per active Collection Executive (plus a Head when they hold figures),
 *    Today / Tomorrow. Every count is a button that narrows the register to that officer, through the
 *    page's existing officer column filter.
 *
 * Collapsed state is remembered per staffer in localStorage, like the other console preferences.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Loader2 } from "lucide-react";
import { InlineOfficerSelect } from "@/components/staff/collections-assign";
import { useCan, useStaffMe } from "@/components/staff/pipeline/hooks";
import { EmptyState, ErrorState, InfoTooltip } from "@/components/ui";
import { PillTabs } from "@/components/ui/pill-tabs";
import {
  collectionsApi,
  dashboardApi,
  paiseToINR,
  type DashTeamDayRow,
  type DashView,
  type WorklistRow,
} from "@/lib/api/applications";
import { BLANK } from "@/lib/staff/column-filter-match";
import { worklistDueCue, worklistDueLabel } from "@/lib/collections/worklist-due";
import { formatDate } from "@/lib/utils";

/** The most unassigned loans the panel lists; the header count is the server's full total. */
const UNASSIGNED_PAGE_SIZE = 200;

type Panel = "assign" | "team";
type Day = "today" | "tomorrow";

export function headDeskPrefsKey(staffId: string): string {
  return `navix-staff-head-desk:${staffId}`;
}

function readCollapsed(staffId: string | undefined): Record<Panel, boolean> {
  const none = { assign: false, team: false };
  if (!staffId) return none;
  try {
    const raw = localStorage.getItem(headDeskPrefsKey(staffId));
    if (!raw) return none;
    const parsed = JSON.parse(raw) as Partial<Record<Panel, boolean>>;
    return { assign: parsed.assign === true, team: parsed.team === true };
  } catch {
    return none;
  }
}

function writeCollapsed(staffId: string | undefined, value: Record<Panel, boolean>) {
  if (!staffId) return;
  try {
    localStorage.setItem(headDeskPrefsKey(staffId), JSON.stringify(value));
  } catch {
    // Private mode / blocked storage — the panel simply opens next time.
  }
}

/** yyyy-mm-dd in IST for `now` shifted by `plusDays` — the backend's `date` is an IST calendar day. */
export function istDayPlus(now: Date, plusDays: number): string {
  const shifted = new Date(now.getTime() + plusDays * 86_400_000);
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(shifted);
}

/**
 * Most overdue first, then due soonest. The server already orders by due date ascending, which is the
 * same thing (DPD is days since the due date), but a cached page could be mid-poll — sort here too.
 */
export function sortUnassigned(rows: WorklistRow[]): WorklistRow[] {
  return [...rows].sort((a, b) => {
    if (a.dpd !== b.dpd) return b.dpd - a.dpd;
    const da = a.loan?.dueDate ?? "";
    const db = b.loan?.dueDate ?? "";
    return da < db ? -1 : da > db ? 1 : a.loanId - b.loanId;
  });
}

function PanelShell({
  title,
  count,
  tooltip,
  collapsed,
  onToggle,
  controls,
  children,
}: {
  title: string;
  count?: number;
  tooltip: string;
  collapsed: boolean;
  onToggle: () => void;
  controls?: React.ReactNode;
  children: React.ReactNode;
}) {
  const id = React.useId();
  return (
    <section className="rounded border border-line bg-white shadow-sm" aria-labelledby={`${id}-h`}>
      <header className="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={!collapsed}
          aria-controls={`${id}-body`}
          className="flex items-center gap-1.5 rounded px-1 py-0.5 text-left hover:bg-grey-100"
        >
          {collapsed ? <ChevronRight size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
          <h2 id={`${id}-h`} className="m-0 font-serif text-sm font-semibold text-navy">
            {title}
            {count != null && <span className="text-muted"> · {count}</span>}
          </h2>
        </button>
        <InfoTooltip content={tooltip} label={`About ${title}`} />
        {!collapsed && controls && <div className="ml-auto">{controls}</div>}
      </header>
      {!collapsed && <div id={`${id}-body`}>{children}</div>}
    </section>
  );
}

function NeedsAssignmentPanel({ collapsed, onToggle, now }: { collapsed: boolean; onToggle: () => void; now: Date }) {
  // The register's own "Unallocated" filter, so this panel and the officer column's checklist agree on
  // what unassigned means; due date ascending IS most-overdue-first (see `sortUnassigned`).
  const q = useQuery({
    queryKey: ["collections-worklist", "unassigned"],
    queryFn: () =>
      collectionsApi.worklistPage({
        filters: { "f.officerName": `in:${BLANK}`, sort: "dueDate", dir: "asc" },
        page: 1,
        size: UNASSIGNED_PAGE_SIZE,
      }),
    refetchInterval: 30_000,
  });
  const rows = React.useMemo(() => sortUnassigned(q.data?.rows ?? []), [q.data?.rows]);
  const total = q.data?.total ?? 0;

  return (
    <PanelShell
      title="Needs assignment"
      count={q.data ? total : undefined}
      tooltip="Live loans in the worklist — past due or due within the week — that no collections executive owns yet. Pick an officer here and the row leaves the list."
      collapsed={collapsed}
      onToggle={onToggle}
    >
      {q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.isLoading ? (
        <p className="flex items-center gap-2 px-3 py-6 text-sm text-muted">
          <Loader2 size={14} className="animate-spin" /> Loading unassigned loans…
        </p>
      ) : rows.length === 0 ? (
        <EmptyState title="Every live case has an officer." />
      ) : (
        <div className="staff-table-scroll max-h-80">
          <table className="staff-data-table">
            <caption className="sr-only">Loans needing a collections executive</caption>
            <thead>
              <tr>
                <th scope="col">Borrower</th>
                <th scope="col">Loan</th>
                <th scope="col" className="num">DPD</th>
                <th scope="col" className="num">Outstanding</th>
                <th scope="col">Assign</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((w) => {
                const cue = worklistDueCue({ dpd: w.dpd, dueDate: w.loan?.dueDate ?? null }, now);
                return (
                  <tr key={w.loanId} data-testid={`unassigned-${w.loanId}`}>
                    <td>
                      <span className="font-semibold text-ink">{w.loan?.borrowerName?.trim() || "—"}</span>
                      {w.loan?.mobile && <span className="ml-2 font-mono text-xs text-muted">{w.loan.mobile}</span>}
                    </td>
                    <td className="font-mono">#{w.loanId}</td>
                    <td className={`num whitespace-nowrap${cue.kind === "overdue" ? " font-semibold text-error-700" : " text-muted"}`}>
                      {worklistDueLabel(cue)}
                    </td>
                    <td className="num font-mono font-semibold">{paiseToINR(w.loan?.outstandingPaise)}</td>
                    <td>
                      <InlineOfficerSelect loanId={w.loanId} officerId={w.assignedOfficerId} officerName={w.assignedOfficerName} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {total > rows.length && (
            <p className="border-t border-line px-3 py-1.5 text-xs text-muted">
              Showing the {rows.length} most overdue of {total}; the rest follow as these are assigned.
            </p>
          )}
        </div>
      )}
    </PanelShell>
  );
}

function CountButton({
  value,
  money,
  onClick,
  label,
}: {
  value: number;
  money?: number;
  onClick?: () => void;
  label: string;
}) {
  const text = money != null ? `${value} · ${paiseToINR(money)}` : String(value);
  if (!onClick || value === 0) {
    return <span className={value === 0 ? "text-muted" : undefined}>{text}</span>;
  }
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      className="rounded px-1 font-semibold text-navy underline decoration-dotted underline-offset-2 hover:bg-navy-tint"
    >
      {text}
    </button>
  );
}

function TeamTodayPanel({
  collapsed,
  onToggle,
  view,
  now,
  onFilterOfficer,
}: {
  collapsed: boolean;
  onToggle: () => void;
  view: DashView;
  now: Date;
  onFilterOfficer?: (staffId: number) => void;
}) {
  const [day, setDay] = React.useState<Day>("today");
  const date = istDayPlus(now, day === "tomorrow" ? 1 : 0);
  const q = useQuery({
    queryKey: ["collections-team-day", view, date],
    queryFn: () => dashboardApi.collectionsTeam(view, date),
    refetchInterval: 30_000,
  });
  const rows = q.data?.rows ?? [];
  const totals = q.data?.totals;
  const cols = day === "today" ? 6 : 3;

  const filter = (r: DashTeamDayRow) =>
    onFilterOfficer && r.staffId != null ? () => onFilterOfficer(r.staffId as number) : undefined;

  const cells = (r: DashTeamDayRow, isTotal: boolean) =>
    day === "today" ? (
      <>
        <td className="num"><CountButton value={r.calls} onClick={filter(r)} label={`Calls logged by ${r.name}`} /></td>
        <td className="num whitespace-nowrap">
          <CountButton value={r.promisesDue} money={r.promisesDuePaise} onClick={filter(r)} label={`Promises due today for ${r.name}`} />
        </td>
        <td className="num whitespace-nowrap">
          <CountButton value={r.partPayments} money={r.partPaymentsPaise} onClick={filter(r)} label={`Part payments today for ${r.name}`} />
        </td>
        <td className={`num font-mono${isTotal ? " font-semibold" : ""}`}>{paiseToINR(r.collectedPaise)}</td>
        <td className="num"><CountButton value={r.closed} onClick={filter(r)} label={`Cases closed today by ${r.name}`} /></td>
      </>
    ) : (
      <>
        <td className="num whitespace-nowrap">
          <CountButton value={r.promisesDue} money={r.promisesDuePaise} onClick={filter(r)} label={`Promises due tomorrow for ${r.name}`} />
        </td>
        <td className="num"><CountButton value={r.dueCount} onClick={filter(r)} label={`Cases falling due tomorrow for ${r.name}`} /></td>
      </>
    );

  return (
    <PanelShell
      title={day === "today" ? "Team today" : "Team tomorrow"}
      tooltip="One row per active collections executive (a Head appears when they hold figures). Today: interactions logged, promises falling due, verified part payments, verified rupees collected and loans closed. Tomorrow: promises and loans falling due. A count narrows the register to that officer."
      collapsed={collapsed}
      onToggle={onToggle}
      controls={
        <PillTabs
          tabs={[
            { key: "today", label: "Today" },
            { key: "tomorrow", label: "Tomorrow" },
          ]}
          active={day}
          onChange={(k: string) => setDay(k as Day)}
        />
      }
    >
      {q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.isLoading ? (
        <p className="flex items-center gap-2 px-3 py-6 text-sm text-muted">
          <Loader2 size={14} className="animate-spin" /> Loading the team’s day…
        </p>
      ) : (
        <div className={`staff-table-scroll max-h-80${q.isFetching && q.data?.date !== date ? " opacity-60" : ""}`}>
          <table className="staff-data-table">
            <caption className="sr-only">Collections team · {formatDate(date)}</caption>
            <thead>
              <tr>
                <th scope="col">Officer</th>
                {day === "today" ? (
                  <>
                    <th scope="col" className="num">Calls logged</th>
                    <th scope="col" className="num">Promises due</th>
                    <th scope="col" className="num">Part payments</th>
                    <th scope="col" className="num">Collected</th>
                    <th scope="col" className="num">Closed</th>
                  </>
                ) : (
                  <>
                    <th scope="col" className="num">Promises due</th>
                    <th scope="col" className="num">Falling due</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.staffId ?? r.name}>
                  <td>
                    <span className="font-semibold text-ink">{r.name}</span>
                    {r.role === "COLLECTION_HEAD" && <span className="ml-2 text-xs text-muted">Head</span>}
                  </td>
                  {cells(r, false)}
                </tr>
              ))}
              {rows.length === 0 && <EmptyState title="No active collections executives." inTable={cols} />}
              {totals && rows.length > 0 && (
                <tr className="bg-grey-50 font-semibold" data-testid="team-totals">
                  <td>Total</td>
                  {cells(totals, true)}
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </PanelShell>
  );
}

/**
 * Mounts above the bucket cards. Renders nothing for anyone without `collections:manage` — a Collection
 * Executive, or while the session is still loading (fail closed, like the assign cell).
 */
export function CollectionsHeadDesk({ onFilterOfficer }: { onFilterOfficer?: (staffId: number) => void }) {
  const me = useStaffMe().data;
  const can = useCan();
  const show = me != null && can("collections:manage");
  const staffId = me?.id;

  const [collapsed, setCollapsed] = React.useState<Record<Panel, boolean>>({ assign: false, team: false });
  // Read the remembered state once the staffer is known; storage is per staff id.
  React.useEffect(() => {
    if (staffId) setCollapsed(readCollapsed(staffId));
  }, [staffId]);
  const toggle = (p: Panel) =>
    setCollapsed((prev) => {
      const next = { ...prev, [p]: !prev[p] };
      writeCollapsed(staffId, next);
      return next;
    });

  if (!show) return null;
  // The Admin working role reads the whole book; a (real or working) Collection Head reads their desk.
  const view: DashView = me.role === "ADMIN" ? "ADMIN" : "COLLECTION_HEAD";
  const now = new Date();

  return (
    <div className="mb-4 grid gap-3 lg:grid-cols-2" data-testid="collections-head-desk">
      <NeedsAssignmentPanel collapsed={collapsed.assign} onToggle={() => toggle("assign")} now={now} />
      <TeamTodayPanel
        collapsed={collapsed.team}
        onToggle={() => toggle("team")}
        view={view}
        now={now}
        onFilterOfficer={onFilterOfficer}
      />
    </div>
  );
}
