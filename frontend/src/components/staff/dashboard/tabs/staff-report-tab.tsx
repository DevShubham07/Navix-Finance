"use client";

import * as React from "react";
import { dashboardApi, type DashBoard, type DashMetric } from "@/lib/api/applications";
import { StarRating } from "@/components/ui/star-rating";
import { ChartCard } from "../chart-card";
import { PCT_TONE_COLOR, TONE_SOLID, pctTone } from "../colors";
import { nf } from "../fmt";
import { fmtScore } from "../rating-factors";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { RankBadge } from "../ui-bits";

interface Cfg {
  board: DashBoard;
  title: string;
  info: string;
  /** Absent for the telecaller board: STAFF_FILES is credit-only server-side and leads are not applications. */
  metric?: DashMetric;
  volumeLabel: string;
  accent: string;
}

const CFG: Record<"credit" | "sales", Cfg> = {
  credit: {
    board: "CREDIT_EXECUTIVE",
    title: "Credit Executive Report",
    info: "Every credit executive ranked by score (0-10): the weighted average of the factors that can be measured in the period. Click a row for their files.",
    metric: "STAFF_FILES",
    volumeLabel: "Files",
    accent: TONE_SOLID.emerald,
  },
  sales: {
    board: "TELECALLER",
    title: "Sales Ops Report",
    info: "Every telecaller ranked by score (0-10): conversion, contact rate and call volume.",
    volumeLabel: "Calls",
    accent: TONE_SOLID.orange,
  },
};

/** Credit Executive Report / Sales Ops Report: the full leaderboard as a factor-by-factor table. */
export function StaffReportTab({ params, open, kind }: DashTabProps & { kind: keyof typeof CFG }) {
  const cfg = CFG[kind];
  const q = useDashQuery("report-" + cfg.board, params, [], () => dashboardApi.leaderboard(params, cfg.board));
  const rows = q.data?.rows ?? [];
  const factorCols = rows.find((r) => r.factors?.length)?.factors ?? [];

  return (
    <ChartCard
      title={cfg.title}
      subtitle={`${nf(q.data?.members ?? rows.length)} members`}
      info={cfg.info}
      accent={cfg.accent}
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      empty={rows.length === 0}
      emptyTitle="No staff on this board"
    >
      <div className="staff-table-scroll">
        <table className="w-full min-w-[36rem] text-left text-xs">
          <thead className="border-b border-line bg-grey-50 text-slate">
            <tr>
              <th className="px-3 py-2 font-semibold">Rank</th>
              <th className="px-3 py-2 font-semibold">Name</th>
              <th className="px-3 py-2 text-right font-semibold">{cfg.volumeLabel}</th>
              {factorCols.map((f) => (
                <th key={f.key} className="px-3 py-2 text-right font-semibold" title={`Weight ${f.weight}`}>
                  {f.label}
                </th>
              ))}
              <th className="px-3 py-2 font-semibold">Stars</th>
              <th className="px-3 py-2 text-right font-semibold">Score</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const tone = PCT_TONE_COLOR[pctTone(r.score == null ? null : r.score / 10)];
              const metric = cfg.metric;
              const go = metric ? () => open({ metric, key: String(r.staffId), title: `${r.name} · ${cfg.title}` }) : undefined;
              return (
                <tr
                  key={r.staffId}
                  tabIndex={go ? 0 : undefined}
                  onClick={go}
                  onKeyDown={go ? (e) => e.key === "Enter" && go() : undefined}
                  title={`${r.name}: score ${fmtScore(r.score)} / 10 · ${nf(r.volume)} ${cfg.volumeLabel.toLowerCase()}`}
                  className={go ? "cursor-pointer border-b border-line hover:bg-grey-50" : "border-b border-line"}
                >
                  <td className="px-3 py-1.5">
                    <RankBadge rank={r.rank} />
                  </td>
                  <td className="px-3 py-1.5 font-semibold text-ink">{r.name}</td>
                  <td className="px-3 py-1.5 text-right font-semibold tabular-nums text-ink">{nf(r.volume)}</td>
                  {factorCols.map((c) => {
                    const f = r.factors?.find((x) => x.key === c.key);
                    const t = PCT_TONE_COLOR[pctTone(f?.value)];
                    return (
                      <td key={c.key} className="px-3 py-1.5 text-right font-semibold tabular-nums" style={{ color: t }}>
                        {f?.display ?? "—"}
                      </td>
                    );
                  })}
                  <td className="px-3 py-1.5">
                    <StarRating value={r.stars} size="0.85rem" />
                  </td>
                  <td className="px-3 py-1.5 text-right text-sm font-bold tabular-nums" style={{ color: tone }}>
                    {fmtScore(r.score)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </ChartCard>
  );
}

export const CreditReportTab = (p: DashTabProps) => <StaffReportTab {...p} kind="credit" />;
export const SalesReportTab = (p: DashTabProps) => <StaffReportTab {...p} kind="sales" />;
