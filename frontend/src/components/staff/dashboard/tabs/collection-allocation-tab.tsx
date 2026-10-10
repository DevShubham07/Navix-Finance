"use client";

import * as React from "react";
import { CheckCircle2, IndianRupee, TrendingUp, UserPlus } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { dashboardApi, paiseToINR, type DashAllocRow } from "@/lib/api/applications";
import { ChartCard } from "../chart-card";
import { DeltaPill } from "../chart-parts";
import { NAVY, TEXT, TONE_SOLID, TONE_TEXT } from "../colors";
import { nf } from "../fmt";
import { deltaPct } from "../kpi-card";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { IconSquare } from "../ui-bits";

/** Collection Allocation: four light stat cards (tone on the icon pill) + the per-collector table (Admin tab and Collection Head view). */
export function CollectionAllocationTab({ params, open }: DashTabProps) {
  const q = useDashQuery("collection-allocation", params, [], () => dashboardApi.collectionAllocation(params));
  const d = q.data;
  const c = d?.cards;
  const prev = c?.previous;

  const cards: {
    label: string;
    value: string;
    prev: number | null | undefined;
    cur: number | undefined;
    icon: LucideIcon;
    tone: keyof typeof TONE_SOLID;
  }[] = [
    { label: "Total Assigned", value: nf(c?.assigned), cur: c?.assigned, prev: prev?.assigned, icon: UserPlus, tone: "blue" },
    { label: "Total Closed", value: nf(c?.closed), cur: c?.closed, prev: prev?.closed, icon: CheckCircle2, tone: "emerald" },
    { label: "Total Collected (Count)", value: nf(c?.collectedCount), cur: c?.collectedCount, prev: prev?.collectedCount, icon: TrendingUp, tone: "sky" },
    { label: "Total Collected (₹)", value: paiseToINR(c?.collectedPaise), cur: c?.collectedPaise, prev: prev?.collectedPaise, icon: IndianRupee, tone: "navy" },
  ];

  const cell = (r: DashAllocRow, text: string, n: number, title: string) =>
    r.staffId != null && n > 0 ? (
      <button
        type="button"
        onClick={() => open({ metric: "STAFF_CASES", key: String(r.staffId), title: `${r.name} · ${title}` })}
        className="font-semibold text-blue-700 underline-offset-2 hover:underline"
      >
        {text}
      </button>
    ) : (
      <span className={n > 0 ? "font-semibold" : "text-muted"}>{text}</span>
    );

  const th = "px-3 py-2 text-right font-semibold";
  return (
    <div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {cards.map((k) => {
          const Icon = k.icon;
          return (
            <div
              key={k.label}
              title={`${k.label}: ${k.value}`}
              className="surface p-5 transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <div className="flex items-start justify-between gap-2">
                <span className="pt-1 text-xs font-medium text-slate">{k.label}</span>
                <IconSquare color={TONE_TEXT[k.tone]}>
                  <Icon size={15} />
                </IconSquare>
              </div>
              <div className="mt-3 flex flex-wrap items-start gap-2">
                <span className="figure-display text-[2.2rem] text-ink">{q.isLoading ? "…" : k.value}</span>
                <DeltaPill pct={deltaPct(k.cur, k.prev)} className="mt-0.5" />
              </div>
            </div>
          );
        })}
      </div>

      <ChartCard
        className="mt-4"
        title="Collection Allocation"
        info="Per collection staffer: cases assigned and closed in the period, and verified money collected on their cases, split Fresh / Re-loan. Click a count to see the cases."
        accent={NAVY}
        loading={q.isLoading}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={!d || d.rows.length === 0}
      >
        {d && (
          <div className="staff-table-scroll">
            <table className="w-full min-w-[46rem] text-xs">
              <thead className="border-b border-line bg-grey-50 text-slate">
                <tr>
                  <th className="px-3 py-2 text-left font-semibold">S.No</th>
                  <th className="px-3 py-2 text-left font-semibold">Collection Ops</th>
                  <th className={th}>Total assigned</th>
                  <th className={th}>Total closed</th>
                  <th className={th}>Fresh (count)</th>
                  <th className={th}>Fresh (₹)</th>
                  <th className={th}>Re-loan (count)</th>
                  <th className={th}>Re-loan (₹)</th>
                  <th className={th}>Total (count)</th>
                  <th className={th}>Total (₹)</th>
                </tr>
              </thead>
              <tbody>
                {d.rows.map((r, i) => (
                  <tr
                    key={r.staffId ?? r.name}
                    className="border-b border-line hover:bg-grey-50"
                    title={`${r.name}: collected ${paiseToINR(r.totalPaise)} on ${nf(r.totalCount)} cases`}
                  >
                    <td className="px-3 py-1.5 text-muted">{i + 1}</td>
                    <td className="px-3 py-1.5 font-semibold text-ink">{r.name}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{cell(r, nf(r.assigned), r.assigned, "assigned cases")}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{cell(r, nf(r.closed), r.closed, "closed cases")}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{nf(r.freshCount)}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums" style={{ color: TEXT.good }}>{paiseToINR(r.freshPaise)}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{nf(r.reloanCount)}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums" style={{ color: TEXT.reloan }}>{paiseToINR(r.reloanPaise)}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{cell(r, nf(r.totalCount), r.totalCount, "collected cases")}</td>
                    <td className="px-3 py-1.5 text-right font-semibold tabular-nums">{paiseToINR(r.totalPaise)}</td>
                  </tr>
                ))}
                <tr className="bg-grey-50 font-semibold text-ink">
                  <td className="px-3 py-2">—</td>
                  <td className="px-3 py-2">GRAND TOTAL</td>
                  <td className="px-3 py-2 text-right tabular-nums">{nf(d.total.assigned)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{nf(d.total.closed)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{nf(d.total.freshCount)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{paiseToINR(d.total.freshPaise)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{nf(d.total.reloanCount)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{paiseToINR(d.total.reloanPaise)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{nf(d.total.totalCount)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{paiseToINR(d.total.totalPaise)}</td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </ChartCard>
    </div>
  );
}
