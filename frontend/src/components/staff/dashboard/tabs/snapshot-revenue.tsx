"use client";

import * as React from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ChevronRight } from "lucide-react";
import {
  dashboardApi,
  paiseToINR,
  type DashBucket,
  type DashGroupBy,
  type DashPreDay,
} from "@/lib/api/applications";
import { formatInrCompact } from "@/lib/staff/format-inr";
import { cn } from "@/lib/utils";
import { ChartCard } from "../chart-card";
import { ChartTooltip } from "../chart-tooltip";
import { AXIS_TICK, BarGradient, CURSOR, GRID_STROKE, PctChip, ProgressBar, activeRow, payloadOf } from "../chart-parts";
import {
  DPD_COLORS,
  DPD_LABELS,
  GRADIENTS,
  NAVY,
  PCT_TONE_COLOR,
  SERIES,
  TEXT,
  TONE_SOLID,
  TONE_TINT,
  fmtPct,
  pctTone,
} from "../colors";
import { fmtDay, fmtDayLong, nf, todayIso } from "../fmt";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";

const chartBox = "h-64 min-h-[240px] w-full";
const dateInput = "rounded border border-line bg-white px-2 py-1 text-xs";
/** Value labels above bars stop being legible past this many bars. */
const LABELS_MAX_BARS = 10;

/** Revenue, Interest & PF, AUM, Pre-Closure week and Collection Analysis. */
export function SnapshotRevenue(props: DashTabProps) {
  return (
    <>
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Revenue {...props} />
        <InterestPf {...props} />
      </div>
      <div className="mt-4">
        <PreclosureWeek {...props} />
      </div>
      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Aum {...props} />
        <CollectionAnalysis {...props} />
      </div>
    </>
  );
}

/* ------------------------------------------------------------------ revenue */

function useDaily({ params }: DashTabProps) {
  return useDashQuery("daily", params, [], () => dashboardApi.daily(params));
}

export function Revenue(props: DashTabProps) {
  const q = useDaily(props);
  const days = q.data?.days ?? [];
  const t = q.data?.totals;
  const data = days.map((d) => ({
    date: d.date,
    label: fmtDay(d.date),
    cumulative: d.cumulativePaise,
    interest: d.interestPaise,
    pf: d.pfPaise,
    penalty: d.penaltyPaise,
  }));
  return (
    <ChartCard
      title="Revenue Performance"
      info="Cumulative revenue: processing fee on loans disbursed that day + interest and penalty on loans closed that day. GST is not revenue."
      accent={SERIES.good}
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      empty={days.length === 0}
      controls={
        t && (
          <>
            <Chip color={SERIES.interest} label="Interest" value={formatInrCompact(t.interestPaise)} />
            <Chip color={SERIES.pf} label="PF" value={formatInrCompact(t.pfPaise)} />
            <Chip color={SERIES.penalty} label="Penalty" value={formatInrCompact(t.penaltyPaise)} />
          </>
        )
      }
    >
      {t && (
        <p className="m-0 mb-2 font-serif text-2xl font-bold" style={{ color: TEXT.good }} title={paiseToINR(t.revenuePaise)}>
          {paiseToINR(t.revenuePaise)} <span className="text-xs font-normal text-muted">total revenue</span>
        </p>
      )}
      <div className={chartBox}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="dg-rev" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={SERIES.good} stopOpacity={0.55} />
                <stop offset="100%" stopColor={SERIES.good} stopOpacity={0.05} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke={GRID_STROKE} vertical={false} />
            <XAxis dataKey="label" tick={AXIS_TICK} minTickGap={16} />
            <YAxis tick={AXIS_TICK} tickFormatter={(v: number) => formatInrCompact(v)} width={58} />
            <Tooltip
              content={
                <ChartTooltip
                  kinds={{ cumulative: "paise", interest: "paise", pf: "paise", penalty: "paise" }}
                  labels={{ cumulative: "Cumulative revenue", interest: "Interest", pf: "Processing fee", penalty: "Penalty" }}
                  title={(_l, row) => fmtDayLong(row?.date as string | undefined)}
                />
              }
            />
            <Area
              type="monotone"
              dataKey="cumulative"
              name="Cumulative revenue"
              stroke={SERIES.good}
              strokeWidth={2.5}
              fill="url(#dg-rev)"
              dot={{ r: 3, fill: SERIES.good }}
              activeDot={{ r: 6 }}
            />
            {/* Hidden helper series so the tooltip can show the day's components. */}
            <Area dataKey="interest" stroke={SERIES.interest} strokeOpacity={0} fill="none" legendType="none" activeDot={false} dot={false} />
            <Area dataKey="pf" stroke={SERIES.pf} strokeOpacity={0} fill="none" legendType="none" activeDot={false} dot={false} />
            <Area dataKey="penalty" stroke={SERIES.penalty} strokeOpacity={0} fill="none" legendType="none" activeDot={false} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}

function Chip({ color, label, value }: { color: string; label: string; value: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full border border-line px-2 py-0.5 text-[11px] font-semibold text-ink">
      <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: color }} />
      {label} {value}
    </span>
  );
}

/* --------------------------------------------------------------- interest & PF */

export function InterestPf(props: DashTabProps) {
  const { open, params } = props;
  // Server-side the day's loans are readable by ADMIN (calendar) and the Disbursement Head; the Accountant has no drill-down.
  const dayMetric = params.view === "ADMIN" ? "CALENDAR_DISBURSED" : params.view === "DISBURSEMENT_HEAD" ? "DISBURSED" : null;
  const q = useDaily(props);
  const days = q.data?.days ?? [];
  const data = days.map((d) => ({ date: d.date, label: fmtDay(d.date), interest: d.interestPaise, pf: d.pfPaise }));
  const labelled = data.length <= LABELS_MAX_BARS;
  return (
    <ChartCard
      title="Interest & PF (daily)"
      info="Per day: interest collected on loans closed that day (blue) and processing fee on loans disbursed that day (green). Click a day to see the loans disbursed."
      accent={SERIES.interest}
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      empty={days.length === 0}
    >
      <div className={chartBox}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            margin={{ top: 18, right: 8, left: 0, bottom: 0 }}
            onClick={(s) => {
              const row = activeRow<{ date: string }>(s);
              if (row && dayMetric)
                open({
                  metric: dayMetric,
                  key: row.date,
                  title: `Loans disbursed · ${fmtDayLong(row.date)}`,
                  range: { from: row.date, to: row.date },
                });
            }}
          >
            <defs>
              <BarGradient id="dg-int" color={SERIES.interest} />
              <BarGradient id="dg-pf" color={SERIES.pf} />
            </defs>
            <CartesianGrid stroke={GRID_STROKE} vertical={false} />
            <XAxis dataKey="label" tick={AXIS_TICK} minTickGap={12} />
            <YAxis tick={AXIS_TICK} tickFormatter={(v: number) => formatInrCompact(v)} width={58} />
            <Tooltip
              cursor={CURSOR}
              content={
                <ChartTooltip
                  kinds={{ interest: "paise", pf: "paise" }}
                  labels={{ interest: "Interest", pf: "Processing fee" }}
                  total="paise"
                  title={(_l, row) => fmtDayLong(row?.date as string | undefined)}
                />
              }
            />
            <Bar dataKey="interest" name="Interest" fill="url(#dg-int)" radius={[6, 6, 0, 0]} cursor="pointer">
              {labelled && <LabelList dataKey="interest" position="top" formatter={(v: number) => formatInrCompact(v)} style={{ fontSize: 9, fill: TEXT.blueDark }} />}
            </Bar>
            <Bar dataKey="pf" name="Processing fee" fill="url(#dg-pf)" radius={[6, 6, 0, 0]} cursor="pointer">
              {labelled && <LabelList dataKey="pf" position="top" formatter={(v: number) => formatInrCompact(v)} style={{ fontSize: 9, fill: TEXT.good }} />}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}

/* ------------------------------------------------------- pre-closure, 7 days */

const WEEK_CELLS = [
  { key: "due", metric: "DUE_ON", label: "Total due", tone: "navy" },
  { key: "pre", metric: "PRECLOSED_ON", label: "Pre-closed", tone: "emerald" },
  { key: "pend", metric: "PENDING_ON", label: "Pending", tone: "amber" },
  { key: "recv", metric: "RECEIVED_ON", label: "Received", tone: "sky" },
] as const;

function weekCell(d: DashPreDay, k: (typeof WEEK_CELLS)[number]["key"]): { count: number; paise: number } {
  switch (k) {
    case "due":
      return { count: d.dueCount, paise: d.dueAmountPaise };
    case "pre":
      return { count: d.preclosedCount, paise: d.preclosedPaise };
    case "pend":
      return { count: d.pendingCount, paise: d.pendingPaise };
    default:
      return { count: d.receivedCount, paise: d.receivedPaise };
  }
}

export function PreclosureWeek({ params, open }: DashTabProps) {
  const [anchor, setAnchor] = React.useState(todayIso());
  const q = useDashQuery("preclosure-week", params, [anchor], () => dashboardApi.preclosureWeek(params, anchor));
  const rows = q.data?.rows ?? [];
  const total = q.data?.total;
  return (
    <ChartCard
      title="Pre-Closure (next 7 days)"
      subtitle="Repayments due in the 7 days after the selected date"
      info="Per due day: loans due, those already closed before the due date, those still pending, and those with a part payment received. Collection % = (pre-closed + received) ÷ total due."
      accent={NAVY}
      controls={
        <input
          type="date"
          value={anchor}
          onChange={(e) => e.target.value && setAnchor(e.target.value)}
          aria-label="Anchor date"
          className={dateInput}
        />
      }
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      empty={rows.length === 0}
    >
      <div className="staff-table-scroll">
        <table className="w-full min-w-[44rem] border-separate border-spacing-y-1.5 text-left text-xs">
          <thead>
            <tr className="text-[11px] uppercase tracking-wide text-muted">
              <th className="px-2 font-semibold">Day</th>
              {WEEK_CELLS.map((c) => (
                <th key={c.key} className="px-2 font-semibold">
                  {c.label}
                </th>
              ))}
              <th className="px-2 font-semibold">Collection %</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.date ?? "x"}>
                <td className="whitespace-nowrap px-2 font-semibold text-navy">{fmtDay(d.date)}</td>
                {WEEK_CELLS.map((c) => {
                  const v = weekCell(d, c.key);
                  return (
                    <td key={c.key} className="p-0">
                      <button
                        type="button"
                        disabled={!d.date}
                        onClick={() =>
                          d.date &&
                          open({
                            metric: c.metric,
                            key: d.date,
                            title: `${c.label} · ${fmtDayLong(d.date)}`,
                            range: { from: d.date, to: d.date },
                          })
                        }
                        title={`${c.label} ${fmtDayLong(d.date)}: ${nf(v.count)} loans · ${paiseToINR(v.paise)}`}
                        className="block w-full rounded-lg px-3 py-2 text-left transition hover:-translate-y-0.5 hover:shadow disabled:hover:translate-y-0"
                        style={{ background: TONE_TINT[c.tone], borderLeft: `3px solid ${GRADIENTS[c.tone][0]}` }}
                      >
                        <span className="block text-sm font-bold" style={{ color: GRADIENTS[c.tone][1] }}>
                          {nf(v.count)} <span className="text-[10px] font-medium">loans</span>
                        </span>
                        <span className="block tabular-nums text-muted">{paiseToINR(v.paise)}</span>
                      </button>
                    </td>
                  );
                })}
                <td className="px-2">
                  <PctChip ratio={d.collectionPct} digits={2} />
                </td>
              </tr>
            ))}
            {total && (
              <tr className="font-bold">
                <td className="px-2 text-navy">Total</td>
                {WEEK_CELLS.map((c) => {
                  const v = weekCell(total, c.key);
                  return (
                    <td key={c.key} className="rounded-lg bg-navy px-3 py-2 text-white">
                      {nf(v.count)} · {paiseToINR(v.paise)}
                    </td>
                  );
                })}
                <td className="px-2">
                  <PctChip ratio={total.collectionPct} digits={2} />
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </ChartCard>
  );
}

/* ----------------------------------------------------------------------- AUM */

function Aum({ params, open }: DashTabProps) {
  const [asOf, setAsOf] = React.useState(todayIso());
  const [mode, setMode] = React.useState<"donut" | "bar">("donut");
  const q = useDashQuery("aum", params, [asOf], () => dashboardApi.aum(params, asOf));
  const rows = (q.data?.rows ?? []).filter((r) => r.bucket !== "TOTAL");
  const total = q.data?.total;
  const chips = q.data?.chips;
  const data = rows.map((r) => ({
    bucket: r.bucket as DashBucket,
    label: DPD_LABELS[r.bucket as DashBucket],
    cases: r.cases,
    principal: r.principalPaise,
    owed: r.owedPaise,
    pct: r.portfolioPct,
  }));
  const openBucket = (b: DashBucket) =>
    open({ metric: "AUM_BUCKET", key: b, title: `AUM · ${DPD_LABELS[b]} (as of ${fmtDayLong(asOf)})`, range: { from: asOf, to: asOf } });
  const tip = (
    <ChartTooltip
      kinds={{ cases: "count" }}
      labels={{ cases: "Cases" }}
      extra={(row) => [
        { label: "Principal", value: paiseToINR(Number(row.principal)) },
        { label: "Owed", value: paiseToINR(Number(row.owed)) },
        { label: "Of portfolio", value: fmtPct(row.pct as number | null, 1) },
      ]}
    />
  );
  return (
    <ChartCard
      title="Assets Under Management"
      info="Open loans disbursed on or before the as-of date, bucketed by days past due (as-of date − due date). Owed is principal + interest + penalty as of that date."
      accent={NAVY}
      controls={
        <input type="date" value={asOf} onChange={(e) => e.target.value && setAsOf(e.target.value)} aria-label="AUM as of" className={dateInput} />
      }
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      empty={rows.length === 0}
    >
      <div className="mb-3 flex flex-wrap gap-2">
        {data.map((d) => (
          <button
            key={d.bucket}
            type="button"
            onClick={() => openBucket(d.bucket)}
            title={`${d.label}: ${nf(d.cases)} cases · ${paiseToINR(d.principal)} principal`}
            className="rounded-full px-3 py-1 text-xs font-semibold text-white hover:-translate-y-0.5 hover:shadow"
            style={{ background: DPD_COLORS[d.bucket] }}
          >
            {d.label} · {nf(d.cases)}
          </button>
        ))}
      </div>
      {total && (
        <div className="mb-3 flex flex-wrap gap-2 text-xs font-semibold text-white">
          <span className="rounded-full bg-navy px-3 py-1">Cases {nf(total.cases)}</span>
          <span className="rounded-full bg-navy px-3 py-1">Principal {paiseToINR(total.principalPaise)}</span>
          <span className="rounded-full bg-navy px-3 py-1">Owed {paiseToINR(total.owedPaise)}</span>
        </div>
      )}
      {chips && (
        <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
          {(
            [
              ["Current", chips.currentPct, chips.currentCount, DPD_COLORS.RUNNING],
              ["1-60 days", chips.d1to60Pct, chips.d1to60Count, DPD_COLORS.D1_30],
              ["61-90 days", chips.d61to90Pct, chips.d61to90Count, DPD_COLORS.D61_90],
              ["90+ days", chips.d90PlusPct, chips.d90PlusCount, DPD_COLORS.D90_PLUS],
            ] as const
          ).map(([l, pct, n, c]) => (
            <div key={l} className="rounded-lg border border-line px-3 py-1.5" style={{ borderLeft: `4px solid ${c}` }}>
              <span className="block text-[11px] text-muted">{l}</span>
              <span className="text-sm font-bold" style={{ color: c }}>
                {fmtPct(pct, 1)}
              </span>
              <span className="ml-1 text-[11px] text-muted">{nf(n)} cases</span>
            </div>
          ))}
        </div>
      )}

      <div className="mb-2 flex items-center justify-between">
        <h4 className="m-0 text-xs font-semibold text-navy">DPD Buckets Visualization</h4>
        <div role="group" aria-label="Chart type" className="flex gap-1 rounded-full bg-grey-100 p-0.5">
          {(["donut", "bar"] as const).map((m) => (
            <button
              key={m}
              type="button"
              aria-pressed={mode === m}
              onClick={() => setMode(m)}
              className={cn("rounded-full px-3 py-0.5 text-[11px] font-semibold", mode === m ? "bg-navy text-white" : "text-muted")}
            >
              {m === "donut" ? "Donut" : "Bar"}
            </button>
          ))}
        </div>
      </div>
      <div className={chartBox}>
        <ResponsiveContainer width="100%" height="100%">
          {mode === "donut" ? (
            <PieChart>
              <Tooltip content={tip} />
              <Pie
                data={data}
                dataKey="cases"
                nameKey="label"
                innerRadius="55%"
                outerRadius="85%"
                paddingAngle={2}
                cursor="pointer"
                onClick={(d) => {
                  const row = payloadOf<{ bucket: DashBucket }>(d);
                  if (row) openBucket(row.bucket);
                }}
              >
                {data.map((d) => (
                  <Cell key={d.bucket} fill={DPD_COLORS[d.bucket]} />
                ))}
              </Pie>
            </PieChart>
          ) : (
            <BarChart
              data={data}
              margin={{ top: 18, right: 8, left: 0, bottom: 0 }}
              onClick={(s) => {
                const row = activeRow<{ bucket: DashBucket }>(s);
                if (row) openBucket(row.bucket);
              }}
            >
              <CartesianGrid stroke={GRID_STROKE} vertical={false} />
              <XAxis dataKey="label" tick={AXIS_TICK} />
              <YAxis tick={AXIS_TICK} allowDecimals={false} width={36} />
              <Tooltip cursor={CURSOR} content={tip} />
              <Bar dataKey="cases" name="Cases" radius={[6, 6, 0, 0]} cursor="pointer">
                {data.map((d) => (
                  <Cell key={d.bucket} fill={DPD_COLORS[d.bucket]} />
                ))}
                <LabelList dataKey="cases" position="top" style={{ fontSize: 11, fontWeight: 600, fill: NAVY }} />
              </Bar>
            </BarChart>
          )}
        </ResponsiveContainer>
      </div>

      <div className="staff-table-scroll mt-3">
        <table className="w-full min-w-[26rem] text-left text-xs">
          <thead className="bg-navy text-white">
            <tr>
              {["Bucket", "Cases", "Principal", "Owed", "% of cases"].map((h) => (
                <th key={h} className="px-3 py-1.5 font-semibold">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr
                key={d.bucket}
                tabIndex={0}
                onClick={() => openBucket(d.bucket)}
                onKeyDown={(e) => e.key === "Enter" && openBucket(d.bucket)}
                className="cursor-pointer border-b border-line hover:bg-grey-50"
              >
                <td className="px-3 py-1.5 font-semibold" style={{ color: DPD_COLORS[d.bucket] }}>
                  {d.label}
                </td>
                <td className="px-3 py-1.5 tabular-nums">{nf(d.cases)}</td>
                <td className="px-3 py-1.5 tabular-nums">{paiseToINR(d.principal)}</td>
                <td className="px-3 py-1.5 tabular-nums">{paiseToINR(d.owed)}</td>
                <td className="px-3 py-1.5">{fmtPct(d.pct, 1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </ChartCard>
  );
}

/* ------------------------------------------------------ collection analysis */

function CollectionAnalysis({ params, open }: DashTabProps) {
  const [date, setDate] = React.useState(todayIso());
  const [groupBy, setGroupBy] = React.useState<DashGroupBy>("EXEC");
  const q = useDashQuery("collection-analysis", params, [date, groupBy], () =>
    dashboardApi.collectionAnalysis(params, date, groupBy),
  );
  const rows = q.data?.rows ?? [];
  return (
    <ChartCard
      title="Collection Analysis"
      info="Loans due on the selected date, grouped by assigned executive or by state. % = collected ÷ total repayable (closed or fully paid)."
      accent={TONE_SOLID.orange}
      controls={
        <>
          <div role="group" aria-label="Group by" className="flex gap-1 rounded-full bg-grey-100 p-0.5">
            {(
              [
                ["EXEC", "By Credit Exec"],
                ["STATE", "By State"],
              ] as const
            ).map(([k, l]) => (
              <button
                key={k}
                type="button"
                aria-pressed={groupBy === k}
                onClick={() => setGroupBy(k)}
                className={cn("rounded-full px-3 py-0.5 text-[11px] font-semibold", groupBy === k ? "bg-navy text-white" : "text-muted")}
              >
                {l}
              </button>
            ))}
          </div>
          <input type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Collection date" className={dateInput} />
        </>
      }
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      empty={rows.length === 0}
      emptyTitle="No loans due on this date"
    >
      <ul className="m-0 list-none space-y-2 p-0">
        {rows.map((r) => {
          const tone = pctTone(r.collectedPct);
          return (
            <li key={r.key}>
              <button
                type="button"
                onClick={() =>
                  open({
                    metric: "COLLECTION_GROUP",
                    key: `${groupBy}:${r.key}:${date}`,
                    title: `${r.label} · due ${fmtDayLong(date)}`,
                    range: { from: date, to: date },
                  })
                }
                className="w-full rounded-lg border border-line px-3 py-2 text-left hover:-translate-y-0.5 hover:shadow"
              >
                <span className="flex flex-wrap items-center gap-2">
                  <span className="rounded-md bg-navy px-2 py-0.5 text-xs font-semibold text-white">{r.label}</span>
                  <span className="rounded bg-grey-100 px-1.5 py-0.5 text-[10px] font-semibold text-muted">
                    {groupBy === "EXEC" ? "Credit Executive" : "State"}
                  </span>
                  <span className="ml-auto">
                    <PctChip ratio={r.collectedPct} digits={1} />
                  </span>
                  <ChevronRight size={14} aria-hidden className="text-muted" />
                </span>
                <ProgressBar ratio={r.collectedPct} color={PCT_TONE_COLOR[tone]} className="my-1.5" />
                <span className="flex flex-wrap gap-x-4 gap-y-0.5 text-[11px] text-muted tabular-nums">
                  <span>{nf(r.loans)} loans</span>
                  <span>Principal {paiseToINR(r.principalPaise)}</span>
                  <span>Repayable {paiseToINR(r.repayablePaise)}</span>
                  <span>
                    Collected {nf(r.collectedCount)}/{nf(r.loans)} · {paiseToINR(r.collectedPaise)}
                  </span>
                  <span>Avg {paiseToINR(r.averagePrincipalPaise)}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </ChartCard>
  );
}
