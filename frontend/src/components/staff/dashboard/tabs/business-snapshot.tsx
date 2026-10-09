"use client";

import * as React from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { CheckCircle2, Clock, FileText, IndianRupee, Scale, Timer, TrendingUp, XCircle } from "lucide-react";
import { dashboardApi, paiseToINR, type DashKpi, type DashMetric, type DashRateRow } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { ChartCard, SectionTitle } from "../chart-card";
import { ChartTooltip } from "../chart-tooltip";
import { AXIS_TICK, CURSOR, GRID_STROKE, ProgressBar, activeRow } from "../chart-parts";
import { NAVY, SERIES, TONE_SOLID, TONE_TINT, fmtPct, gradientCss } from "../colors";
import { nf } from "../fmt";
import { KpiCard } from "../kpi-card";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { SnapshotMonthly } from "./snapshot-monthly";
import { SnapshotRevenue } from "./snapshot-revenue";

const chartBox = "h-56 min-h-[240px] w-full";

const shareOf = (part: number, whole: number, noun: string) =>
  whole > 0 ? `${((part / whole) * 100).toFixed(1)}% of ${noun}` : undefined;

/** The Admin "Business Snapshot" tab. */
export function BusinessSnapshot(props: DashTabProps) {
  const { params, open } = props;
  const q = useDashQuery("snapshot", params, [], () => dashboardApi.snapshot(params));
  const s = q.data;

  const openMetric = (metric: DashMetric, title: string, segment?: "FRESH" | "RELOAN") =>
    open({ metric, title, segment });

  // The contract has no card for the average loan size; synthesise one on the DISBURSED metric so a
  // click lists the loans the average is taken over.
  const avg: DashKpi | undefined = s && {
    metric: "DISBURSED",
    count: s.kpis.disbursed.count,
    amountPaise: s.financial.averageLoanPaise,
    fresh: s.kpis.disbursed.fresh,
    reloan: s.kpis.disbursed.reloan,
    freshPaise: s.financial.averageFreshPaise,
    reloanPaise: s.financial.averageReloanPaise,
    previousCount: null,
    previousAmountPaise: null,
  };

  const kpiGrid = "grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4";
  const placeholder = (n: number) => (
    <div className={kpiGrid}>
      {Array.from({ length: n }, (_, i) => (
        <div key={i} className="h-36 animate-pulse rounded-xl bg-grey-100" />
      ))}
    </div>
  );

  const total = s?.kpis.applications.count ?? 0;
  const statusData = s
    ? [
        { key: "DISBURSED", label: "Disbursed", value: s.kpis.disbursed.count, color: SERIES.good },
        { key: "PENDING", label: "Pending", value: s.kpis.pending.count, color: SERIES.pending },
        { key: "REJECTED", label: "Rejected", value: s.kpis.rejected.count, color: SERIES.bad },
      ]
    : [];

  const c = s?.closed;
  const collectedTotal = c?.totalCollectedPaise ?? 0;

  return (
    <div>
      {/* ------------------------------------------------------------- KPIs */}
      <SectionTitle>Key Performance Indicators</SectionTitle>
      {q.isError ? (
        <ChartCard title="Key Performance Indicators" error={q.error} onRetry={() => void q.refetch()} />
      ) : !s ? (
        placeholder(4)
      ) : (
        <div className={kpiGrid}>
          <KpiCard title="Total Applications" tone="navy" icon={FileText} kpi={s.kpis.applications} onOpen={(m) => openMetric(m, "Total applications")} />
          <KpiCard title="Disbursed Cases" tone="emerald" icon={CheckCircle2} kpi={s.kpis.disbursed} shareLabel={shareOf(s.kpis.disbursed.count, total, "applications")} onOpen={(m) => openMetric(m, "Disbursed cases")} />
          <KpiCard title="Pending Cases" tone="orange" icon={Clock} kpi={s.kpis.pending} shareLabel={shareOf(s.kpis.pending.count, total, "applications")} onOpen={(m) => openMetric(m, "Pending cases")} />
          <KpiCard title="Rejected Cases" tone="red" icon={XCircle} kpi={s.kpis.rejected} shareLabel={shareOf(s.kpis.rejected.count, total, "applications")} onOpen={(m) => openMetric(m, "Rejected cases")} />
        </div>
      )}

      <SectionTitle>Financial Overview</SectionTitle>
      {q.isError ? null : !s || !avg ? (
        placeholder(4)
      ) : (
        <div className={kpiGrid}>
          <KpiCard title="Total Loan Amount" tone="violet" icon={IndianRupee} mode="money" kpi={s.financial.totalLoan} onOpen={(m) => openMetric(m, "Total loan amount (disbursed)")} />
          <KpiCard title="Pending Sanctioned Amount" tone="teal" icon={Scale} mode="money" kpi={s.financial.pendingSanctioned} onOpen={(m) => openMetric(m, "Pending sanctioned")} />
          <KpiCard title="Pending Amount" tone="sky" icon={Timer} mode="money" kpi={s.financial.pendingDisbursal} onOpen={(m) => openMetric(m, "Pending disbursal")} />
          <KpiCard title="Average Loan Size" tone="royal" icon={TrendingUp} mode="money" kpi={avg} onOpen={(m) => openMetric(m, "Disbursed loans (average size)")} />
        </div>
      )}

      {/* -------------------------------------------- status / closed / collected */}
      <div className="mt-6 grid grid-cols-1 gap-4 xl:grid-cols-3">
        <ChartCard
          title="Status Distribution"
          info="Applications created in the period (excluding drafts) by outcome: disbursed, still pending, or rejected."
          accent={SERIES.pending}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          empty={!s}
        >
          <div className={chartBox}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={statusData}
                margin={{ top: 20, right: 8, left: 0, bottom: 0 }}
                onClick={(st) => {
                  const row = activeRow<{ key: DashMetric; label: string }>(st);
                  if (row) openMetric(row.key, `${row.label} cases`);
                }}
              >
                <CartesianGrid stroke={GRID_STROKE} vertical={false} />
                <XAxis dataKey="label" tick={AXIS_TICK} />
                <YAxis tick={AXIS_TICK} allowDecimals={false} width={32} />
                <Tooltip
                  cursor={CURSOR}
                  content={
                    <ChartTooltip
                      kinds={{ value: "count" }}
                      labels={{ value: "Applications" }}
                      extra={(row) => [{ label: "Share", value: total > 0 ? `${((Number(row.value) / total) * 100).toFixed(1)}%` : "—" }]}
                    />
                  }
                />
                <Bar dataKey="value" name="Applications" radius={[6, 6, 0, 0]} cursor="pointer">
                  {statusData.map((d) => (
                    <Cell key={d.key} fill={d.color} />
                  ))}
                  <LabelList dataKey="value" position="top" style={{ fontSize: 12, fontWeight: 700, fill: NAVY }} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </ChartCard>

        <ChartCard
          title="Closed Cases"
          info="Loans whose closed date falls in the period. Settlement = closed through an approved settlement. Part payment = still-open loans with a verified part payment in the period."
          accent={SERIES.good}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          empty={!c}
        >
          {c && (
            <>
              <button
                type="button"
                onClick={() => openMetric("CLOSED", "Closed + settlement loans")}
                className="mb-3 w-full rounded-lg px-4 py-3 text-left text-white shadow transition hover:-translate-y-0.5 hover:shadow-lg"
                style={{ backgroundImage: gradientCss("emerald") }}
              >
                <span className="block text-xs font-semibold uppercase tracking-wide text-white/80">Closed + Settlement</span>
                <span className="font-serif text-3xl font-bold">{nf(c.closedCount)}</span>
                <span className="ml-2 text-xs text-white/80">
                  {fmtPct(c.closedPctOfDisbursed, 1)} of disbursed · Click to view loans
                </span>
              </button>
              {(
                [
                  ["CLOSED", "Closed (incl. settlement)", c.closedCount, "emerald"],
                  ["SETTLED", "of which Settlement", c.settledCount, "violet"],
                  ["PART_PAID", "Part payment", c.partPaidCount, "amber"],
                ] as const
              ).map(([metric, label, n, tone]) => (
                <button
                  key={metric}
                  type="button"
                  onClick={() => openMetric(metric, label)}
                  className="mb-2 block w-full rounded-lg px-1 py-1 text-left hover:bg-grey-50"
                >
                  <span className="flex justify-between text-xs">
                    <span className="font-medium text-ink">{label}</span>
                    <span className="font-bold tabular-nums" style={{ color: TONE_SOLID[tone] }}>
                      {nf(n)}
                    </span>
                  </span>
                  <ProgressBar ratio={c.closedCount > 0 ? n / c.closedCount : 0} color={TONE_SOLID[tone]} className="mt-1" />
                </button>
              ))}
            </>
          )}
        </ChartCard>

        <ChartCard
          title="Collection Amount"
          info="Verified payments received in the period, split by how the loan stands: closed normally, settled, or part-paid and still open."
          accent={SERIES.collected}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          empty={!c}
        >
          {c && (
            <>
              <p className="m-0 mb-3">
                <span className="inline-block rounded-full bg-navy px-4 py-1.5 text-sm font-bold text-white">
                  Total Collected {paiseToINR(c.totalCollectedPaise)}
                </span>
              </p>
              {(
                [
                  ["CLOSED", "Closed loans", c.collectedClosedPaise, "emerald"],
                  ["SETTLED", "Settlements", c.collectedSettledPaise, "violet"],
                  ["PART_PAID", "Part payments", c.collectedPartPaise, "amber"],
                ] as const
              ).map(([metric, label, paise, tone]) => (
                <button
                  key={metric}
                  type="button"
                  onClick={() => openMetric(metric, `${label} (collected)`)}
                  className="mb-2 block w-full rounded-lg px-1 py-1 text-left hover:bg-grey-50"
                  title={`${label}: ${paiseToINR(paise)} · ${fmtPct(collectedTotal > 0 ? paise / collectedTotal : null, 1)} of collected`}
                >
                  <span className="flex justify-between text-xs">
                    <span className="font-medium text-ink">{label}</span>
                    <span className="font-bold tabular-nums" style={{ color: TONE_SOLID[tone] }}>
                      {paiseToINR(paise)}
                    </span>
                  </span>
                  <ProgressBar ratio={collectedTotal > 0 ? paise / collectedTotal : 0} color={TONE_SOLID[tone]} className="mt-1" />
                </button>
              ))}
            </>
          )}
        </ChartCard>
      </div>

      {/* ---------------------------------------------------------- monthly */}
      <div className="mt-6">
        <SnapshotMonthly {...props} />
      </div>

      {/* ------------------------------------------- revenue / AUM / analysis */}
      <div className="mt-6">
        <SnapshotRevenue {...props} />
      </div>

      {/* ----------------------------------------------- PF % / ROI % tables */}
      <div className="mt-6 grid grid-cols-1 gap-4 xl:grid-cols-2">
        <RateTable
          title="PF % Table"
          info="Loans disbursed in the period grouped by processing-fee percentage."
          rows={s?.pfTable}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          metric="PF_RATE"
          open={open}
        />
        <RateTable
          title="ROI % Table"
          info="Loans disbursed in the period grouped by daily interest rate."
          rows={s?.roiTable}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          metric="ROI_RATE"
          open={open}
        />
      </div>

      {/* ----------------------------------------------- performance summary */}
      <ChartCard
        className="mt-6"
        title="Performance Summary"
        info="Disbursed, still-pending and rejected applications as a share of all applications in the period."
        accent={NAVY}
        loading={q.isLoading}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={!s}
      >
        {s && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {(
              [
                ["Disbursement rate", s.rates.disbursementRate, "DISBURSED", "emerald"],
                ["Pending rate", s.rates.pendingRate, "PENDING", "orange"],
                ["Rejection rate", s.rates.rejectionRate, "REJECTED", "red"],
              ] as const
            ).map(([label, ratio, metric, tone]) => (
              <button
                key={label}
                type="button"
                onClick={() => openMetric(metric, label)}
                className={cn("rounded-xl px-4 py-5 text-center transition hover:-translate-y-0.5 hover:shadow-lg")}
                style={{ background: TONE_TINT[tone], border: `2px solid ${TONE_SOLID[tone]}` }}
              >
                <span className="block font-serif text-4xl font-bold" style={{ color: TONE_SOLID[tone] }}>
                  {fmtPct(ratio, 1)}
                </span>
                <span className="text-xs font-semibold text-ink">{label}</span>
              </button>
            ))}
          </div>
        )}
      </ChartCard>
    </div>
  );
}

export function RateTable({
  title,
  info,
  rows,
  loading,
  error,
  onRetry,
  metric,
  open,
}: {
  title: string;
  info: string;
  rows: DashRateRow[] | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  metric: "PF_RATE" | "ROI_RATE";
  open: DashTabProps["open"];
}) {
  const link = (r: DashRateRow, seg: "FRESH" | "RELOAN", n: number) => (
    <button
      type="button"
      disabled={n === 0}
      onClick={() =>
        open({
          metric,
          key: String(r.ratePct),
          segment: seg,
          title: `${title} · ${r.ratePct}% · ${seg === "FRESH" ? "New" : "Repeat"}`,
        })
      }
      className="font-semibold text-blue-700 underline-offset-2 hover:underline disabled:text-muted disabled:no-underline"
    >
      {nf(n)}
    </button>
  );
  return (
    <ChartCard title={title} info={info} accent={NAVY} loading={loading} error={error} onRetry={onRetry} empty={!rows || rows.length === 0}>
      <div className="staff-table-scroll">
        <table className="w-full min-w-[34rem] text-left text-xs">
          <thead className="bg-navy text-white">
            <tr>
              {["Rate %", "New", "Repeat", "Total cases", "Principal", "Net disbursed", "Total repayable"].map((h) => (
                <th key={h} className="px-3 py-2 font-semibold">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {(rows ?? []).map((r) => (
              <tr key={r.ratePct} className="border-b border-line hover:bg-grey-50">
                <td className="px-3 py-1.5 font-bold text-navy">{r.ratePct}%</td>
                <td className="px-3 py-1.5">{link(r, "FRESH", r.newCount)}</td>
                <td className="px-3 py-1.5">{link(r, "RELOAN", r.repeatCount)}</td>
                <td className="px-3 py-1.5 tabular-nums">{nf(r.totalCases)}</td>
                <td className="px-3 py-1.5 tabular-nums">{paiseToINR(r.principalPaise)}</td>
                <td className="px-3 py-1.5 tabular-nums">{paiseToINR(r.netDisbursedPaise)}</td>
                <td className="px-3 py-1.5 tabular-nums">{paiseToINR(r.totalRepayablePaise)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </ChartCard>
  );
}
