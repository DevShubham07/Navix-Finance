"use client";

import * as React from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ComposedChart,
  LabelList,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Pencil } from "lucide-react";
import { dashboardApi, paiseToINR, type DashMonthRow } from "@/lib/api/applications";
import { formatInrCompact } from "@/lib/staff/format-inr";
import { ChartCard } from "../chart-card";
import { ChartTooltip } from "../chart-tooltip";
import { BAR, CURSOR, DeltaPill, GRID, PctChip, VALUE_LABEL, X_AXIS, Y_AXIS, activeRow } from "../chart-parts";
import { NAVY, PCT_TONE_COLOR, SERIES, TEXT, TONE_SOLID, WHITE, fmtPct, pctTone } from "../colors";
import { fmtMonth, fmtMonthShort, monthRange, nf } from "../fmt";
import { deltaPct } from "../kpi-card";
import { Gauge } from "../gauge";
import type { DashTabProps } from "../tab-props";
import { TargetsDialog } from "../targets-dialog";
import { useDashQuery } from "../use-dash-query";

const MONTHS_BACK = 4;

const chartBox = "h-64 min-h-[240px] w-full";
const selectCls = "h-8 cursor-pointer rounded-full border border-line bg-paper px-3 text-xs font-medium text-ink shadow-pill";

/** Monthly widgets of the Business Snapshot: collection %, pre-closure gauge, deficit, target vs achieved, retention, marketing. */
/** `topOnly` renders just the collection %, pre-closure and deficit cards (the collection role views). */
export function SnapshotMonthly({ params, open, realAdmin, topOnly = false }: DashTabProps & { topOnly?: boolean }) {
  const q = useDashQuery("monthly", params, [MONTHS_BACK], () => dashboardApi.monthly(params, MONTHS_BACK));
  const months = q.data?.months ?? [];
  const latest = months[months.length - 1];

  const [gaugeMonth, setGaugeMonth] = React.useState<string | undefined>();
  const [deficitMonth, setDeficitMonth] = React.useState<string | undefined>();
  const [targetMonth, setTargetMonth] = React.useState<string | undefined>();
  const [editTargets, setEditTargets] = React.useState(false);

  const openMonth = (m: string, title: string) =>
    open({ metric: "COLLECTION_MONTH", key: m, title: `${title} · ${fmtMonth(m)}`, range: monthRange(m) });

  const shared = { loading: q.isLoading, error: q.error, onRetry: () => void q.refetch(), empty: months.length === 0 };

  // ---- Collection % -------------------------------------------------------
  const collData = months.map((m) => ({
    month: m.month,
    label: fmtMonthShort(m.month),
    tloan: m.totalRepayablePaise,
    collected: m.collectedPaise,
    pct: m.collectionPct == null ? null : m.collectionPct * 100,
    targetBp: m.targetBp,
    deficitAmountPaise: m.deficitAmountPaise,
    dueLoans: m.dueLoans,
  }));

  // ---- Deficit ------------------------------------------------------------
  const dm: DashMonthRow | undefined = months.find((m) => m.month === deficitMonth) ?? latest;
  const deficit = dm?.deficitAmountPaise ?? 0;

  // ---- Target vs achieved -------------------------------------------------
  const tm: DashMonthRow | undefined = months.find((m) => m.month === targetMonth) ?? latest;
  const tmIdx = tm ? months.indexOf(tm) : -1;
  const prevAchieved = tmIdx > 0 ? months[tmIdx - 1].achievedPct : null;
  const targetData = months.map((m) => ({
    month: m.month,
    label: fmtMonthShort(m.month),
    target: m.disbursalTargetPaise ?? 0,
    achieved: m.disbursedPaise,
    achievedPct: m.achievedPct,
    hasTarget: m.disbursalTargetPaise != null,
  }));

  // ---- Retention ----------------------------------------------------------
  const r = q.data?.retention;
  const retData = r
    ? [
        { key: "DUE", label: "Due", value: r.due, color: TONE_SOLID.blue },
        { key: "CLOSED", label: "Closed", value: r.closed, color: SERIES.pending },
        { key: "RELOAN", label: "Re-loan", value: r.reloan, color: SERIES.good },
        { key: "NO_REPEAT", label: "No repeat", value: r.noRepeat, color: TONE_SOLID.orange },
      ]
    : [];

  // ---- Marketing ----------------------------------------------------------
  const mk = q.data?.marketing ?? [];
  const mkData = mk.map((m) => ({
    month: m.month,
    label: fmtMonthShort(m.month),
    pct: m.conversionPct == null ? null : m.conversionPct * 100,
    leads: m.leads,
    converted: m.converted,
  }));
  const mkLast = mk[mk.length - 1];
  const mkPrev = mk[mk.length - 2];

  return (
    <>
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        {/* Collection % ------------------------------------------------- */}
        <ChartCard
          title={`Collection % (last ${MONTHS_BACK} months)`}
          info="Per due month: collected ÷ total repayable of the loans due that month. Verified payments only."
          accent={SERIES.collected}
          {...shared}
        >
          <div className="mb-2 flex flex-wrap gap-2">
            {months.map((m) => (
              <button
                key={m.month}
                type="button"
                onClick={() => openMonth(m.month, "Loans due")}
                title={`${fmtMonth(m.month)}: ${nf(m.dueLoans)} loans due · target ${(m.targetBp / 100).toFixed(0)}%`}
                className="rounded-xl border border-line bg-paper px-2.5 py-1 text-center shadow-xs transition hover:-translate-y-0.5 hover:shadow-md"
              >
                <span className="block text-[10px] font-semibold text-muted">{fmtMonthShort(m.month)}</span>
                <span className="block text-sm font-bold" style={{ color: PCT_TONE_COLOR[pctTone(m.collectionPct)] }}>
                  {fmtPct(m.collectionPct, 2)}
                </span>
              </button>
            ))}
          </div>
          <div className={chartBox}>
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart
                data={collData}
                margin={{ top: 16, right: 8, left: 0, bottom: 0 }}
                onClick={(s) => {
                  const row = activeRow<{ month: string }>(s);
                  if (row) openMonth(row.month, "Loans due");
                }}
              >
                <CartesianGrid {...GRID} />
                <XAxis dataKey="label" {...X_AXIS} />
                <YAxis yAxisId="amt" {...Y_AXIS} tickFormatter={(v: number) => formatInrCompact(v)} width={58} />
                <YAxis yAxisId="pct" orientation="right" domain={[0, 100]} {...Y_AXIS} tickFormatter={(v: number) => `${v}%`} width={40} />
                <Tooltip
                  cursor={CURSOR}
                  content={
                    <ChartTooltip
                      kinds={{ tloan: "paise", collected: "paise", pct: "pct" }}
                      labels={{ tloan: "T.Loan", collected: "Collected", pct: "Collection %" }}
                      extra={(row) => [
                        { label: "Loans due", value: nf(Number(row.dueLoans)) },
                        { label: "Target", value: `${(Number(row.targetBp) / 100).toFixed(0)}%` },
                        { label: "Deficit", value: paiseToINR(Number(row.deficitAmountPaise)) },
                      ]}
                    />
                  }
                />
                <Bar yAxisId="amt" dataKey="tloan" name="T.Loan" fill={SERIES.tloan} {...BAR} cursor="pointer" />
                <Bar yAxisId="amt" dataKey="collected" name="Collected" fill={SERIES.collected} {...BAR} cursor="pointer" />
                <Line
                  yAxisId="pct"
                  type="monotone"
                  dataKey="pct"
                  name="Collection %"
                  stroke={SERIES.pctLine}
                  strokeWidth={2.5}
                  dot={false}
                  activeDot={{ r: 5, strokeWidth: 3, stroke: WHITE }}
                >
                  <LabelList dataKey="pct" position="top" formatter={(v: number) => (v == null ? "" : `${v.toFixed(1)}%`)} style={VALUE_LABEL} />
                </Line>
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <Legend3 />
        </ChartCard>

        {/* Pre-closure gauge ------------------------------------------- */}
        <ChartCard
          title={`Pre-Closure % (last ${MONTHS_BACK} months)`}
          info="Loans due in the month that were closed BEFORE their due date, ÷ all loans due that month."
          accent={SERIES.good}
          {...shared}
        >
          <Gauge
            months={months.map((m) => ({
              key: m.month,
              label: fmtMonthShort(m.month),
              pct: m.preclosurePct,
              loans: m.dueLoans,
            }))}
            selected={gaugeMonth ?? latest?.month}
            onSelect={setGaugeMonth}
            onOpen={(m) => openMonth(m, "Loans due (pre-closure base)")}
            caption={`${nf(months.find((m) => m.month === (gaugeMonth ?? latest?.month))?.preclosedCount)} closed before due date`}
          />
        </ChartCard>

        {/* Deficit ------------------------------------------------------- */}
        <ChartCard
          title="Deficit Collection"
          info="Shortfall against the month's collection-% target (default 88%): max(0, target amount − collected)."
          accent={deficit > 0 ? SERIES.bad : SERIES.good}
          controls={
            <select
              aria-label="Deficit month"
              className={selectCls}
              value={dm?.month ?? ""}
              onChange={(e) => setDeficitMonth(e.target.value)}
            >
              {months.map((m) => (
                <option key={m.month} value={m.month}>
                  {fmtMonth(m.month)}
                </option>
              ))}
            </select>
          }
          {...shared}
        >
          <div className="mb-3 text-center">
            <button
              type="button"
              onClick={() => dm && openMonth(dm.month, "Loans due")}
              className="rounded-lg px-4 py-1 hover:bg-grey-50"
              title="Click to view the loans behind this month"
            >
              <span className="figure-display block text-[2.2rem]" style={{ color: deficit > 0 ? TEXT.bad : TEXT.good }}>
                {paiseToINR(deficit)}
              </span>
              <span className="text-xs text-muted">Deficit · {dm ? fmtMonth(dm.month) : ""}</span>
            </button>
          </div>
          <ul className="m-0 list-none space-y-2 p-0">
            {months.map((m) => {
              const bad = (m.deficitPct ?? 0) > 0;
              return (
                <li key={m.month}>
                  <button
                    type="button"
                    onClick={() => openMonth(m.month, "Loans due")}
                    className="w-full rounded-xl border border-line bg-paper px-3 py-2 text-left text-xs shadow-xs transition hover:-translate-y-0.5 hover:shadow-md"
                  >
                    <span className="flex items-center justify-between">
                      <span className="flex items-center gap-1.5 font-semibold text-ink">
                        <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: bad ? SERIES.bad : SERIES.good }} />
                        {fmtMonthShort(m.month)}
                      </span>
                      <span className="text-muted">
                        Target {(m.targetBp / 100).toFixed(0)}% · Actual {fmtPct(m.collectionPct, 2)} ·{" "}
                        <b style={{ color: bad ? TEXT.bad : TEXT.good }}>Deficit {fmtPct(m.deficitPct, 2)}</b>
                      </span>
                    </span>
                    <span className="mt-1 flex justify-between tabular-nums text-muted">
                      <span>Collected {paiseToINR(m.collectedPaise)}</span>
                      <span>Target amt {paiseToINR(m.targetAmountPaise)}</span>
                      <b style={{ color: bad ? TEXT.bad : TEXT.good }}>{paiseToINR(m.deficitAmountPaise)}</b>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </ChartCard>
      </div>

      {!topOnly && (
      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        {/* Target vs achieved ------------------------------------------ */}
        <ChartCard
          title="Target vs Achieved"
          info="Principal disbursed in the month versus the admin-set disbursal target. Achieved % is blank when no target is set."
          accent={NAVY}
          controls={
            <>
              <select
                aria-label="Target month"
                className={selectCls}
                value={tm?.month ?? ""}
                onChange={(e) => setTargetMonth(e.target.value)}
              >
                {months.map((m) => (
                  <option key={m.month} value={m.month}>
                    {fmtMonth(m.month)}
                  </option>
                ))}
              </select>
              {realAdmin && (
                <button
                  type="button"
                  onClick={() => setEditTargets(true)}
                  className="flex items-center gap-1 rounded border border-line px-2 py-1 text-xs font-semibold text-navy hover:bg-grey-100"
                >
                  <Pencil size={12} /> Edit targets
                </button>
              )}
            </>
          }
          {...shared}
        >
          <div className="mb-2 flex items-center gap-2">
            <span className="figure-display text-[2.2rem]" style={{ color: PCT_TONE_COLOR[pctTone(tm?.achievedPct)] }}>
              {fmtPct(tm?.achievedPct, 1)}
            </span>
            <span className="text-xs text-muted">achieved</span>
            <DeltaPill pct={deltaPct(tm?.achievedPct, prevAchieved)} />
          </div>
          <div className={chartBox}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={targetData}
                margin={{ top: 16, right: 8, left: 0, bottom: 0 }}
                onClick={(s) => {
                  const row = activeRow<{ month: string }>(s);
                  if (row)
                    open({ metric: "DISBURSED", title: `Disbursed · ${fmtMonth(row.month)}`, range: monthRange(row.month) });
                }}
              >
                <CartesianGrid {...GRID} />
                <XAxis dataKey="label" {...X_AXIS} />
                <YAxis {...Y_AXIS} tickFormatter={(v: number) => formatInrCompact(v)} width={58} />
                <Tooltip
                  cursor={CURSOR}
                  content={
                    <ChartTooltip
                      kinds={{ target: "paise", achieved: "paise" }}
                      labels={{ target: "Target", achieved: "Achieved" }}
                      extra={(row) => [
                        {
                          label: "Achieved %",
                          value: row.hasTarget ? fmtPct(row.achievedPct as number | null, 1) : "No target set",
                        },
                      ]}
                    />
                  }
                />
                <Bar dataKey="target" name="Target" fill={SERIES.target} {...BAR} cursor="pointer" />
                <Bar dataKey="achieved" name="Achieved" fill={SERIES.neutral} {...BAR} cursor="pointer">
                  <LabelList dataKey="achieved" position="top" formatter={(v: number) => formatInrCompact(v)} style={VALUE_LABEL} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </ChartCard>

        {/* Retention ----------------------------------------------------- */}
        <ChartCard
          title="Re-loan Retention"
          info="Of loans due in the period, how many are closed, and of those how many customers came back for a new loan."
          accent={TONE_SOLID.violet}
          controls={r ? <PctChip ratio={r.retentionPct} /> : null}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          empty={!r}
        >
          <div className={chartBox}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={retData}
                margin={{ top: 20, right: 8, left: 0, bottom: 0 }}
                onClick={(s) => {
                  const row = activeRow<{ key: string; label: string }>(s);
                  if (row) open({ metric: "RELOAN_RETENTION", key: row.key, title: `Re-loan retention · ${row.label}` });
                }}
              >
                <CartesianGrid {...GRID} />
                <XAxis dataKey="label" {...X_AXIS} />
                <YAxis {...Y_AXIS} allowDecimals={false} width={32} />
                <Tooltip
                  cursor={CURSOR}
                  content={
                    <ChartTooltip
                      kinds={{ value: "count" }}
                      labels={{ value: "Loans" }}
                      extra={(row) => [
                        {
                          label: "Share of due",
                          value: r && r.due > 0 ? `${((Number(row.value) / r.due) * 100).toFixed(1)}%` : "—",
                        },
                      ]}
                    />
                  }
                />
                <Bar dataKey="value" name="Loans" {...BAR} cursor="pointer">
                  {retData.map((d) => (
                    <Cell key={d.key} fill={d.color} />
                  ))}
                  <LabelList dataKey="value" position="top" style={VALUE_LABEL} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          {r && (
            <p className="m-0 mt-2 flex flex-wrap justify-between gap-2 text-[11px] text-muted">
              <span>Due {nf(r.due)}</span>
              <span>Closed {nf(r.closed)}</span>
              <span>Re-loan {nf(r.reloan)}</span>
              <span>No repeat {nf(r.noRepeat)}</span>
            </p>
          )}
        </ChartCard>

        {/* Marketing conversion ----------------------------------------- */}
        <ChartCard
          title="Marketing Conversion"
          info="Leads created in the month whose PAN later became a disbursed loan. Leads are marketing records, not applications, so there is no drill-down."
          accent={TONE_SOLID.blue}
          controls={
            mkLast ? (
              <>
                <span className="text-sm font-bold text-navy">{fmtPct(mkLast.conversionPct, 1)}</span>
                <DeltaPill pct={deltaPct(mkLast.conversionPct, mkPrev?.conversionPct)} />
              </>
            ) : null
          }
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          empty={mk.length === 0}
          emptyTitle="No marketing data"
        >
          <div className={chartBox}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={mkData} margin={{ top: 20, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid {...GRID} />
                <XAxis dataKey="label" {...X_AXIS} />
                <YAxis {...Y_AXIS} tickFormatter={(v: number) => `${v}%`} width={40} />
                <Tooltip
                  cursor={CURSOR}
                  content={
                    <ChartTooltip
                      kinds={{ pct: "pct" }}
                      labels={{ pct: "Conversion" }}
                      extra={(row) => [
                        { label: "Leads", value: nf(Number(row.leads)) },
                        { label: "Converted", value: nf(Number(row.converted)) },
                      ]}
                    />
                  }
                />
                <Bar dataKey="pct" name="Conversion" fill={TONE_SOLID.blue} {...BAR}>
                  <LabelList dataKey="pct" position="top" formatter={(v: number) => (v == null ? "" : `${v.toFixed(1)}%`)} style={VALUE_LABEL} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </ChartCard>
      </div>

      )}

      {realAdmin && !topOnly && <TargetsDialog open={editTargets} onClose={() => setEditTargets(false)} />}
    </>
  );
}

function Legend3() {
  const items: [string, string][] = [
    ["T.Loan", SERIES.tloan],
    ["Collected", SERIES.collected],
    ["Collection %", SERIES.pctLine],
  ];
  return (
    <ul className="m-0 mt-2 flex list-none flex-wrap justify-center gap-4 p-0 text-[11px] text-muted">
      {items.map(([l, c]) => (
        <li key={l} className="flex items-center gap-1.5">
          <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: c }} />
          {l}
        </li>
      ))}
    </ul>
  );
}
