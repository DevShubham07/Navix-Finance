"use client";

import * as React from "react";
import { Ban, Percent, Wallet } from "lucide-react";
import { dashboardApi, paiseToINR, type DashMetric, type DashSnapshot } from "@/lib/api/applications";
import {
  CHART_COLORS,
  CapsuleBarChart,
  DeltaChip,
  Figure,
  GoalProgress,
  MetricTile,
  Panel,
  PanelHeader,
  SegmentBars,
  StatBlock,
  TargetColumns,
} from "@/components/kit";
import { ErrorState } from "@/components/ui";
import { fmtPct } from "../colors";
import { fmtMonthShort } from "../fmt";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";

/** Six months reads as a trend in the capsule chart without crowding it. */
const OVERVIEW_MONTHS = 6;

/** "₹4.06Cr" / "₹8.4L" / "₹62,400" — headline figures only; drill-downs keep exact rupees. */
export function compactINR(paise: number | null | undefined): string {
  if (paise == null) return "—";
  const r = paise / 100;
  if (Math.abs(r) >= 1e7) return `₹${(r / 1e7).toFixed(2)}Cr`;
  if (Math.abs(r) >= 1e5) return `₹${(r / 1e5).toFixed(1)}L`;
  return paiseToINR(paise);
}

const pctChange = (cur: number | null | undefined, prev: number | null | undefined) =>
  cur == null || prev == null || prev === 0 ? null : Math.round(((cur - prev) / prev) * 1000) / 10;

/**
 * The Business Snapshot's at-a-glance band, in the kit's composition (see /sample): disbursal trend
 * as capsules, the business mix as split bars, three metric tiles, collections against amount due,
 * and the one dark "collection efficiency" card. Every figure comes from the same snapshot/monthly
 * contracts the detail sections below read, so the two can never disagree.
 */
export function SnapshotOverview({ s, params, open }: { s: DashSnapshot } & Pick<DashTabProps, "params" | "open">) {
  const m = useDashQuery("monthly", params, [OVERVIEW_MONTHS], () => dashboardApi.monthly(params, OVERVIEW_MONTHS));
  const months = m.data?.months ?? [];
  const openMetric = (metric: DashMetric, title: string) => open({ metric, title });

  const disbursed = s.kpis.disbursed;
  const apps = s.kpis.applications;
  const pos = s.position;

  const capsules = months.map((row, i) => ({
    label: fmtMonthShort(row.month),
    value: row.disbursedPaise,
    delta: i > 0 ? pctChange(row.disbursedPaise, months[i - 1].disbursedPaise) : null,
  }));
  const targets = months
    .filter((row) => row.totalRepayablePaise > 0)
    .map((row) => ({ label: fmtMonthShort(row.month).split(" ")[0], target: row.totalRepayablePaise, actual: row.collectedPaise }));
  const dueTotal = months.reduce((a, r) => a + r.totalRepayablePaise, 0);
  const collectedTotal = months.reduce((a, r) => a + r.collectedPaise, 0);
  const efficiency = dueTotal > 0 ? collectedTotal / dueTotal : null;
  const monthsMet = targets.filter((t) => t.actual >= t.target).length;

  return (
    <div className="mb-2 space-y-3">
      {/* ---------------------------------------------------------- row 1 */}
      <div className="grid gap-3 lg:grid-cols-[1.65fr_1fr]">
        <Panel>
          <PanelHeader title="Disbursed" subtitle={`${disbursed.count.toLocaleString("en-IN")} loans in the selected period`} />
          <button
            type="button"
            onClick={() => openMetric("DISBURSED", "Disbursed cases")}
            className="-mt-2 flex items-start gap-2 rounded-xl text-left outline-none focus-visible:ring-2 focus-visible:ring-gold/40"
            aria-label={`Disbursed ${paiseToINR(disbursed.amountPaise)}. Open the records.`}
          >
            <Figure size="lg">{compactINR(disbursed.amountPaise)}</Figure>
            <DeltaChip pct={pctChange(disbursed.amountPaise, disbursed.previousAmountPaise)} className="mt-1" />
          </button>
          {m.isError ? (
            <ErrorState error={m.error} onRetry={() => void m.refetch()} className="py-6" />
          ) : capsules.length ? (
            <CapsuleBarChart data={capsules} format={compactINR} ariaLabel="Amount disbursed per month" />
          ) : (
            <div className="mt-10 h-48 animate-pulse rounded-2xl bg-grey-100" />
          )}
        </Panel>

        <Panel>
          <PanelHeader title="Business mix" />
          <StatBlock
            label="Applications"
            value={apps.count.toLocaleString("en-IN")}
            delta={pctChange(apps.count, apps.previousCount)}
            size="md"
            className="-mt-2 mb-7"
          />
          <SegmentBars
            rows={[
              {
                label: "Outcome: disbursed · pending · rejected",
                segments: [
                  { label: "Disbursed", value: disbursed.count, color: CHART_COLORS.mint },
                  { label: "Pending", value: s.kpis.pending.count, color: CHART_COLORS.sun },
                  { label: "Rejected", value: s.kpis.rejected.count, color: CHART_COLORS.ember },
                ],
              },
              {
                label: "Disbursed amount: fresh · re-loan",
                segments: [
                  { label: "Fresh", value: (disbursed.freshPaise ?? 0) / 100, color: CHART_COLORS.violet },
                  { label: "Re-loan", value: (disbursed.reloanPaise ?? 0) / 100, color: CHART_COLORS.ink },
                ],
              },
            ]}
            scale="row"
            format={(v) => (v > 1000 ? compactINR(v * 100) : v.toLocaleString("en-IN"))}
          />
          <p className="m-0 mt-7 max-w-xs text-xs text-muted">
            Disbursement rate {fmtPct(s.rates.disbursementRate)} · rejection rate {fmtPct(s.rates.rejectionRate)}.
          </p>
        </Panel>
      </div>

      {/* ---------------------------------------------------------- row 2 */}
      <div className="grid gap-3 xl:grid-cols-[1.45fr_1fr]">
        <Panel>
          <PanelHeader title="Collections" subtitle="Collected against amount due, by due month" />
          {m.isError ? (
            <ErrorState error={m.error} onRetry={() => void m.refetch()} className="py-6" />
          ) : (
            <div className="grid gap-6 lg:grid-cols-[190px_1fr] lg:items-end">
              <div className="flex flex-col gap-5">
                <StatBlock label={`Collected, ${OVERVIEW_MONTHS} months`} value={compactINR(collectedTotal)} size="lg" />
                <StatBlock
                  label="of amount due"
                  value={compactINR(dueTotal)}
                  size="sm"
                  caption={targets.length ? `${monthsMet} of ${targets.length} months collected in full.` : undefined}
                />
              </div>
              {targets.length ? (
                <TargetColumns data={targets} format={compactINR} ariaLabel="Collected versus due per month" />
              ) : (
                <div className="h-48 animate-pulse rounded-2xl bg-grey-100" />
              )}
            </div>
          )}
        </Panel>

        <div className="grid gap-3">
          <div className="grid gap-3 sm:grid-cols-3">
            <MetricTile value={fmtPct(s.rates.disbursementRate, 0)} label="Disbursement rate" icon={<Percent size={15} strokeWidth={1.8} />} />
            <MetricTile value={compactINR(s.financial.averageLoanPaise)} label="Average ticket" icon={<Wallet size={15} strokeWidth={1.8} />} />
            <MetricTile value={fmtPct(s.rates.rejectionRate, 0)} label="Rejection rate" icon={<Ban size={15} strokeWidth={1.8} />} />
          </div>

          <Panel tone="dark" className="flex min-h-[14rem] flex-col justify-between p-6">
            <span aria-hidden className="pointer-events-none absolute inset-0 opacity-60 [background:repeating-linear-gradient(90deg,rgb(255_255_255/.05)_0_2px,transparent_2px_26px)]" />
            <div className="relative flex items-start justify-between gap-3">
              <p className="m-0 text-base font-medium">Collection efficiency</p>
              <span className="text-[11px] text-white/50">{OVERVIEW_MONTHS} months</span>
            </div>
            <div className="relative flex items-start">
              <Figure size="xl">{efficiency == null ? "—" : Math.round(efficiency * 100)}</Figure>
              {efficiency != null && <span className="figure-display mt-1 text-2xl">%</span>}
            </div>
            {pos ? (
              <GoalProgress
                className="relative"
                tone="inverse"
                ratio={pos.receivablePaise > 0 ? pos.receivedPaise / pos.receivablePaise : 0}
                label={`Received ${compactINR(pos.receivedPaise)} of ${compactINR(pos.receivablePaise)} receivable`}
                trailing={`${compactINR(pos.pendingPaise)} pending`}
              />
            ) : null}
          </Panel>
        </div>
      </div>
    </div>
  );
}
