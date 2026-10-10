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
import { Banknote, CheckCircle2, Clock, FileText, Hourglass, IndianRupee, Landmark, Receipt, Scale, Timer, TrendingUp, TriangleAlert, XCircle } from "lucide-react";
import { dashboardApi, paiseToINR, type DashKpi, type DashMetric, type DashRateRow } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { ChartCard, SectionTitle } from "../chart-card";
import { ChartTooltip } from "../chart-tooltip";
import { BAR, CURSOR, GRID, ProgressBar, VALUE_LABEL, X_AXIS, Y_AXIS, activeRow } from "../chart-parts";
import { NAVY, SERIES, TONE_SOLID, TONE_TEXT, fmtPct } from "../colors";
import { nf } from "../fmt";
import { KpiCard } from "../kpi-card";
import { PositionCard } from "../position-card";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { SnapshotMonthly } from "./snapshot-monthly";
import { SnapshotOverview } from "./snapshot-overview";
import { SnapshotRevenue } from "./snapshot-revenue";

const chartBox = "h-56 min-h-[240px] w-full";

const shareOf = (part: number, whole: number, noun: string) =>
  whole > 0 ? `${((part / whole) * 100).toFixed(1)}% of ${noun}` : undefined;

/** The Admin "Business Snapshot" tab. */
export function BusinessSnapshot(props: DashTabProps) {
  const { params, open } = props;
  const q = useDashQuery("snapshot", params, [], () => dashboardApi.snapshot(params));
  const s = q.data;
  const pos = s?.position; // absent on an older backend

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
        <div key={i} className="h-36 animate-pulse rounded-[22px] bg-grey-100" />
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
      {/* ------------------------------------------------------------- overview */}
      {s && <SnapshotOverview s={s} params={params} open={open} />}

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
          <KpiCard title="Rejected Cases" tone="red" icon={XCircle} kpi={s.kpis.rejected} lowerIsBetter shareLabel={shareOf(s.kpis.rejected.count, total, "applications")} onOpen={(m) => openMetric(m, "Rejected cases")} />
        </div>
      )}

      {!q.isError && (!s || pos) && (
        <>
          <SectionTitle>Business position</SectionTitle>
          <p className="-mt-2 mb-3 text-xs text-muted">Loans disbursed in the selected period</p>
        </>
      )}
      {q.isError || (s && !pos) ? null : !pos ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="h-28 animate-pulse rounded-[22px] bg-grey-100" />
          ))}
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
          {(
            [
              ["Principal disbursed", "navy", Banknote, pos.principalPaise, "POSITION_PRINCIPAL", undefined, "Sum of principal on loans disbursed in the period."],
              ["Net disbursed", "teal", Landmark, pos.netDisbursedPaise, "POSITION_NET", "after fee + GST", "Cash actually sent to borrowers: principal less processing fee and GST."],
              ["Net receivable", "violet", Receipt, pos.receivablePaise, "POSITION_RECEIVABLE", "principal + interest to date", "Principal plus interest accrued to date, excluding late penalty."],
              ["Total penalty", "red", TriangleAlert, pos.penaltyPaise, "POSITION_PENALTY", undefined, "Late penalty accrued on these loans."],
              ["Net received", "emerald", CheckCircle2, pos.receivedPaise, "POSITION_RECEIVED", "verified payments", "Verified repayments received against these loans."],
              ["Pending", "orange", Hourglass, pos.pendingPaise, "POSITION_PENDING", pos.waivedPaise > 0 ? `owed today · ${paiseToINR(pos.waivedPaise)} waived` : "owed today, after settlements", "Still owed today on these loans, after settlements."],
            ] as const
          ).map(([title, tone, icon, paise, metric, caption, definition]) => (
            <PositionCard
              key={metric}
              title={title}
              tone={tone}
              icon={icon}
              paise={paise}
              loans={pos.loans}
              metric={metric}
              caption={caption}
              definition={definition}
              detail={
                metric === "POSITION_PENDING" ? (
                  <>
                    {pos.waivedPaise > 0 && (
                      <p className="m-0 mt-1.5 tabular-nums">Waived via settlements: {paiseToINR(pos.waivedPaise)}</p>
                    )}
                    {pos.overpaidPaise > 0 && (
                      <p className="m-0 mt-1.5 tabular-nums">Overpaid by borrowers: {paiseToINR(pos.overpaidPaise)}</p>
                    )}
                    <p className="m-0 mt-1.5 text-slate">Receivable + penalty − received − waived + overpaid</p>
                  </>
                ) : undefined
              }
              onOpen={(m) => openMetric(m, title)}
            />
          ))}
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
                <CartesianGrid {...GRID} />
                <XAxis dataKey="label" {...X_AXIS} />
                <YAxis {...Y_AXIS} allowDecimals={false} width={32} />
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
                <Bar dataKey="value" name="Applications" {...BAR} cursor="pointer">
                  {statusData.map((d) => (
                    <Cell key={d.key} fill={d.color} />
                  ))}
                  <LabelList dataKey="value" position="top" style={VALUE_LABEL} />
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
                className="surface-tile mb-4 w-full px-4 py-3 text-left transition hover:-translate-y-0.5 hover:shadow-md"
              >
                <span className="block text-xs font-medium text-slate">Closed + Settlement</span>
                <span className="mt-1 flex flex-wrap items-end gap-x-2">
                  <span className="figure-display text-[2.2rem] text-ink">{nf(c.closedCount)}</span>
                  <span className="pb-1 text-xs text-slate">
                    {fmtPct(c.closedPctOfDisbursed, 1)} of disbursed · Click to view loans
                  </span>
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
                    <span className="font-bold tabular-nums" style={{ color: TONE_TEXT[tone] }}>
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
              <p className="m-0 mb-4">
                <span className="block text-xs font-medium text-slate">Total Collected</span>
                <span className="figure-display mt-1 block text-[2.2rem] text-ink">{paiseToINR(c.totalCollectedPaise)}</span>
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
                    <span className="font-bold tabular-nums" style={{ color: TONE_TEXT[tone] }}>
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
                className={cn("surface-tile flex flex-col items-center px-4 py-5 text-center transition hover:-translate-y-0.5 hover:shadow-md")}
              >
                <span className="figure-display block text-[2.9rem]" style={{ color: TONE_TEXT[tone] }}>
                  {fmtPct(ratio, 1)}
                </span>
                <span className="mt-2 flex items-center gap-1.5 text-xs font-medium text-ink">
                  <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: TONE_SOLID[tone] }} />
                  {label}
                </span>
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
          <thead className="border-b border-line bg-grey-50 text-slate">
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
