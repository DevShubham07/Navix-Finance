"use client";

/**
 * One staffer's figures for the selected period, explained — the pop-up behind a row on
 * `/staff/performance`.
 *
 * Leads with the parameters of the person's own job (a Collection Executive sees calls, contact
 * rate, promises and money recovered; a Credit Head sees assignments), each with the sentence that
 * says what it counts. Anything else they did outside that job — a Head working a file personally,
 * an Admin in a working role — follows under "Also did", so nothing they did is hidden. Built only
 * from the row already on the page: no extra request, so it always agrees with the table.
 */

import * as React from "react";
import Link from "next/link";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip as RTooltip, XAxis, YAxis } from "recharts";
import { X } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ROLE_LABEL } from "@/components/staff/live-pipeline";
import type { StaffPerformanceRow } from "@/lib/api/applications";
import { cn, formatDateTime } from "@/lib/utils";
import {
  ACTION_LABEL,
  PERFORMANCE_ENTITIES,
  col,
  entityForRole,
  formatMetric,
  type Metric,
} from "@/lib/staff/performance-entities";

const NAVY = "#0C2540";

function MetricCard({ metric, row, note }: { metric: Metric; row: StaffPerformanceRow; note?: string }) {
  const v = metric.value(row);
  const tone =
    metric.tone === "success" ? "text-success-700" : metric.tone === "error" ? "text-error-700" : "text-navy";
  return (
    <div className={cn("rounded border bg-white p-3", metric.live ? "border-gold-soft" : "border-line")}>
      <div className="flex items-baseline justify-between gap-2">
        <div className="text-xs font-semibold text-muted">{metric.label}</div>
        {metric.live && <span className="text-[10px] font-semibold uppercase tracking-wide text-muted">Live</span>}
      </div>
      <div className={cn("mt-0.5 font-serif text-xl font-bold tabular-nums", tone)}>{formatMetric(metric.fmt, v)}</div>
      <p className="mt-1 text-[11px] leading-snug text-muted">
        {metric.info}
        {note && metric.calls ? ` ${note}` : ""}
      </p>
    </div>
  );
}

/** A metric is worth an "Also did" card only when it measured something non-zero. */
function hasValue(m: Metric, r: StaffPerformanceRow): boolean {
  const v = m.value(r);
  return v != null && v !== 0;
}

export function StaffPerformanceDialog({
  row,
  periodLabel,
  from,
  to,
  callsNote,
  onClose,
}: {
  row: StaffPerformanceRow;
  periodLabel: string;
  from?: string;
  to?: string;
  callsNote?: string;
  onClose: () => void;
}) {
  const own = entityForRole(row.role);
  const ownKeys = new Set(own.metrics.map((m) => m.key));
  // Everything else they did, from every other entity's parameters, de-duplicated by key.
  const extra: Metric[] = [];
  for (const e of PERFORMANCE_ENTITIES) {
    if (e.key === own.key) continue;
    for (const m of e.metrics) {
      if (ownKeys.has(m.key) || extra.some((x) => x.key === m.key) || m.key === "activeDays") continue;
      if (m.key === "totalActions" || m.key === "callsMade" || m.key === "moneyPaise") continue;
      if (hasValue(m, row)) extra.push(m);
    }
  }

  const c = col(row);
  const outcomes = [
    { label: "Connected", n: c.connected },
    { label: "Promise to pay", n: c.promisesToPay },
    { label: "Paid", n: c.paidOutcomes },
    { label: "No answer", n: c.noAnswer },
    { label: "Disputed", n: c.disputed },
  ];
  const otherOutcomes = c.interactions - outcomes.reduce((n, o) => n + o.n, 0);
  if (otherOutcomes > 0) outcomes.push({ label: "Other", n: otherOutcomes });

  const actions = Object.entries(row.actionCounts ?? {}).sort(([, a], [, b]) => b - a);
  const daily = row.daily ?? [];
  const titleId = `perf-dialog-${row.staffId}`;

  return (
    <Dialog open onClose={onClose} size="lg" aria-labelledby={titleId}>
      <DialogHeader>
        <div className="flex items-start justify-between gap-3">
          <div>
            <DialogTitle id={titleId}>
              {row.staffName}
              {!row.active && <span className="ml-2 text-sm font-normal text-muted">(inactive)</span>}
            </DialogTitle>
            <div className="text-sm text-muted">
              {ROLE_LABEL[row.role] ?? row.role} · {periodLabel}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
          >
            <X size={18} />
          </button>
        </div>
        <p className="text-xs text-muted">
          {own.label}: {own.blurb}
        </p>
      </DialogHeader>

      <div className="max-h-[70vh] space-y-5 overflow-y-auto pr-1">
        <section>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
            {own.key === "ALL" ? "Activity" : `As ${own.label}`}
          </h4>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {own.metrics.map((m) => (
              <MetricCard key={m.key} metric={m} row={row} note={callsNote} />
            ))}
          </div>
        </section>

        {extra.length > 0 && (
          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Also did</h4>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {extra.map((m) => (
                <MetricCard key={m.key} metric={m} row={row} note={callsNote} />
              ))}
            </div>
          </section>
        )}

        {c.interactions > 0 && (
          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
              Call outcomes ({c.interactions.toLocaleString("en-IN")})
            </h4>
            <div className="space-y-1.5">
              {outcomes
                .filter((o) => o.n > 0)
                .map((o) => (
                  <div key={o.label} className="flex items-center gap-3 text-sm">
                    <span className="w-32 shrink-0 text-muted">{o.label}</span>
                    <div className="h-2 flex-1 overflow-hidden rounded bg-grey-100">
                      <div className="h-full bg-navy" style={{ width: `${(o.n / c.interactions) * 100}%` }} />
                    </div>
                    <span className="w-20 shrink-0 text-right tabular-nums">
                      {o.n} · {Math.round((o.n / c.interactions) * 100)}%
                    </span>
                  </div>
                ))}
            </div>
          </section>
        )}

        {daily.length > 0 && (
          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Activity per day</h4>
            <div style={{ width: "100%", height: 150 }}>
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
          </section>
        )}

        {actions.length > 0 && (
          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">File actions by type</h4>
            <table className="w-full text-sm">
              <tbody>
                {actions.map(([a, n]) => (
                  <tr key={a} className="border-b border-line last:border-0">
                    <td className="py-1 text-muted">{ACTION_LABEL[a] ?? a}</td>
                    <td className="py-1 text-right tabular-nums">{n}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line pt-3 text-xs text-muted">
          <span>
            First action {row.firstActionAt ? formatDateTime(row.firstActionAt) : "—"} · Last{" "}
            {row.lastActionAt ? formatDateTime(row.lastActionAt) : "—"}
          </span>
          {actions.length > 0 && (
            <Link
              href={`/staff/my-decisions?staffId=${row.staffId}${from ? `&from=${from}` : ""}${to ? `&to=${to}` : ""}`}
              className="font-semibold text-navy underline"
            >
              Open decision history
            </Link>
          )}
        </div>
      </div>
    </Dialog>
  );
}
