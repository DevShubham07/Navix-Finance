"use client";

import * as React from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  dashboardApi,
  paiseToINR,
  type DashBoard,
  type DashChart,
  type DashMetric,
  type DashParams,
  type DashRoleCard,
  type DashRoleView,
} from "@/lib/api/applications";
import { useStaffSession } from "@/lib/auth/staff-session";
import { formatInrCompact } from "@/lib/staff/format-inr";
import { cn } from "@/lib/utils";
import { ChartCard } from "../chart-card";
import { ChartTooltip, type TooltipKind } from "../chart-tooltip";
import { BAR, CURSOR, DeltaPill, GRID, X_AXIS, Y_AXIS, activeRow } from "../chart-parts";
import { SERIES, TONE_SOLID, TONE_TEXT, TONE_TINT, WHITE } from "../colors";
import { fmtAxisLabel, fmtDay, nf } from "../fmt";
import { deltaPct } from "../kpi-card";
import { Leaderboard } from "../leaderboard";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { chartBox } from "../ui-bits";

export function useRoleView(params: DashParams) {
  return useDashQuery<DashRoleView>("role-view", params, [], () => dashboardApi.roleView(params));
}

/**
 * The staff id a STAFF_FILES / STAFF_CASES click should filter to: the single selected staffer, or
 * the signed-in user when they are looking at their own (executive / telecaller) view. An
 * aggregated Head / Admin view passes no key, so the drawer lists everything in scope.
 */
export function useOwnStaffKey(params: DashParams): string | undefined {
  const { session } = useStaffSession();
  if (params.staffIds?.length === 1) return String(params.staffIds[0]);
  const own = session?.realRole === params.view && !params.view.endsWith("_HEAD") && params.view !== "ACCOUNTANT";
  return own && session ? session.id : undefined;
}

/* -------------------------------------------------------------- leaderboards */

/** One ranked board (own = royal dot, child = teal) whose rows open the files / cases drawer. */
export function BoardPanel({
  params,
  open,
  board,
  title,
  tone,
  metric,
}: Pick<DashTabProps, "params" | "open"> & {
  board: DashBoard;
  title: string;
  tone: "royal" | "teal";
  /** Records metric a row opens. Omit when the board has none (the telecaller board: leads and calls are not applications). */
  metric?: "STAFF_FILES" | "STAFF_CASES";
}) {
  const q = useDashQuery("leaderboard", params, [board], () => dashboardApi.leaderboard(params, board));
  return (
    <Leaderboard
      title={title}
      tone={tone}
      data={q.data}
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
      onOpenStaff={metric ? (r) => open({ metric, key: String(r.staffId), title: `${r.name} · ${title}` }) : undefined}
    />
  );
}

/* --------------------------------------------------------------------- cards */

const fmtCard = (c: DashRoleCard): { main: string; sub?: string } =>
  c.count != null
    ? { main: nf(c.count), sub: c.amountPaise != null ? paiseToINR(c.amountPaise) : undefined }
    : { main: paiseToINR(c.amountPaise) };

/**
 * Role-view stat cards. `tint` = compact white tiles with a tone dot and a pale count badge (Lead
 * Overview); `gradient` = the larger light-panel KPI look (display-face figure, delta pill, the tone
 * only on the dot). The variant name is historical. Cards with a `metric` open the drawer.
 */
export function RoleCards({
  cards,
  open,
  staffKey,
  variant = "tint",
  titlePrefix,
}: {
  cards: DashRoleCard[];
  open: DashTabProps["open"];
  staffKey?: string;
  variant?: "tint" | "gradient";
  titlePrefix?: string;
}) {
  const grid = "grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4";
  return (
    <div className={grid}>
      {cards.map((c) => {
        const { main, sub } = fmtCard(c);
        const prev = c.count != null ? c.previousCount : c.previousAmountPaise;
        const delta = deltaPct(c.count ?? c.amountPaise, prev);
        const isStaff = c.metric === "STAFF_FILES" || c.metric === "STAFF_CASES";
        const go = c.metric
          ? () =>
              open({
                metric: c.metric as DashMetric,
                key: isStaff ? staffKey : undefined,
                title: `${titlePrefix ? `${titlePrefix} · ` : ""}${c.label}`,
              })
          : undefined;
        const solid = variant === "gradient";
        const goodWhenUp = c.key !== "rejected" && c.key !== "failed" && c.key !== "rejectedPayments";
        const body = (
          <>
            <span className="flex items-start justify-between gap-2">
              <span className="flex items-center gap-1.5 text-xs font-medium text-slate">
                <span aria-hidden className="h-2 w-2 shrink-0 rounded-full" style={{ background: TONE_SOLID[c.tone] }} />
                {c.label}
              </span>
              {!solid && c.count != null && (
                <span
                  className="rounded-full px-2 py-0.5 text-[10px] font-semibold"
                  style={{ background: TONE_TINT[c.tone], color: TONE_TEXT[c.tone] }}
                >
                  {nf(c.count)}
                </span>
              )}
            </span>
            <span className={cn("flex flex-wrap items-start gap-2", solid ? "mt-3" : "mt-2")}>
              <span className={cn("figure-display text-ink", solid ? "text-[2.2rem]" : "text-[1.6rem]")}>{main}</span>
              <DeltaPill pct={delta} goodWhenUp={goodWhenUp} className="mt-0.5" />
            </span>
            {sub && <span className="mt-1 block text-xs tabular-nums text-slate">{sub}</span>}
            {go && <span className="mt-1 block text-[10px] text-slate">Click to view records</span>}
          </>
        );
        const cls = cn(
          "flex w-full flex-col text-left transition",
          solid ? "surface p-5" : "surface-tile p-3.5",
          go && "hover:-translate-y-0.5 hover:shadow-md",
        );
        const title = `${c.label}: ${main}${sub ? ` · ${sub}` : ""}${prev != null ? ` (previous period ${c.count != null ? nf(prev) : paiseToINR(prev)})` : ""}`;
        return go ? (
          <button key={c.key} type="button" onClick={go} className={cls} title={title} aria-label={`${title}. Open the records.`}>
            {body}
          </button>
        ) : (
          <div key={c.key} className={cls} title={title}>
            {body}
          </div>
        );
      })}
    </div>
  );
}

/* -------------------------------------------------------------------- charts */

/** Series colours by key; anything unlisted falls through the palette. */
const KEY_COLOR: Record<string, string> = {
  assigned: TONE_SOLID.blue,
  followups: SERIES.good,
  disbursed: SERIES.good,
  disbursedPaise: SERIES.pending,
  leads: TONE_SOLID.blue,
  calls: SERIES.good,
  unclosed: TONE_SOLID.blue,
  closed: SERIES.pending,
  ptpPaise: SERIES.good,
};
const FALLBACK = [TONE_SOLID.blue, SERIES.good, SERIES.pending, TONE_SOLID.violet, TONE_SOLID.orange];
const isPaise = (k: string) => k.endsWith("Paise");

/**
 * A role-view line / bar chart straight from the contract's {id, kind, keys, labels, points}.
 * Second and later count series on a line are dashed; `*Paise` keys read in rupees (own right axis
 * on lines). Hover shows exact values; click a point/bar to `onPoint(date)`.
 */
export function RoleChart({
  chart,
  loading,
  error,
  onRetry,
  onPoint,
  note,
  accent,
}: {
  chart: DashChart | undefined;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  onPoint?: (date: string, chart: DashChart) => void;
  note?: string;
  accent?: string;
}) {
  const data: Array<Record<string, string | number>> = (chart?.points ?? []).map((p) => ({ ...p, label: fmtDay(String(p.date)) }));
  const keys = chart?.keys ?? [];
  const color = (k: string, i: number) => KEY_COLOR[k] ?? FALLBACK[i % FALLBACK.length];
  const kinds: Record<string, TooltipKind> = Object.fromEntries(keys.map((k) => [k, isPaise(k) ? "paise" : "count"]));
  const sums = keys.map((k) => ({ k, v: data.reduce((s, p) => s + (Number(p[k]) || 0), 0) }));
  const hasRight = keys.some(isPaise) && keys.some((k) => !isPaise(k));
  const click = (s: unknown) => {
    const row = activeRow<{ date: string }>(s);
    if (row && chart && onPoint) onPoint(String(row.date), chart);
  };
  const tooltip = (
    <Tooltip
      cursor={CURSOR}
      content={<ChartTooltip kinds={kinds} labels={chart?.labels} title={(_l, row) => fmtAxisLabel(row?.date)} />}
    />
  );
  const common = { data, margin: { top: 16, right: 8, left: 0, bottom: 0 }, onClick: onPoint ? click : undefined };
  const leftTick = (k: string) => (v: number) => (isPaise(k) ? formatInrCompact(v) : String(v));

  return (
    <ChartCard
      title={chart?.title ?? "Chart"}
      accent={accent ?? (keys[0] ? color(keys[0], 0) : undefined)}
      info={note}
      controls={sums.map(({ k, v }) => (
        <span key={k} className="inline-flex items-center gap-1.5 text-[11px] text-slate">
          <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: color(k, keys.indexOf(k)) }} />
          {chart?.labels[k] ?? k} <b className="font-semibold tabular-nums text-ink">{isPaise(k) ? formatInrCompact(v) : nf(v)}</b>
        </span>
      ))}
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={!chart || data.length === 0}
    >
      <div className={chartBox}>
        <ResponsiveContainer width="100%" height="100%">
          {chart?.kind === "bar" ? (
            <BarChart {...common} barGap={4}>
              <CartesianGrid {...GRID} />
              <XAxis dataKey="label" {...X_AXIS} minTickGap={10} />
              <YAxis {...Y_AXIS} allowDecimals={false} width={keys.some(isPaise) ? 58 : 32} tickFormatter={leftTick(keys[0] ?? "")} />
              {tooltip}
              {keys.map((k, i) => (
                <Bar key={k} dataKey={k} name={chart.labels[k] ?? k} fill={color(k, i)} {...BAR} cursor={onPoint ? "pointer" : undefined} />
              ))}
            </BarChart>
          ) : (
            <LineChart {...common}>
              <CartesianGrid {...GRID} />
              <XAxis dataKey="label" {...X_AXIS} minTickGap={10} />
              <YAxis yAxisId="n" {...Y_AXIS} allowDecimals={false} width={32} />
              {hasRight && (
                <YAxis yAxisId="p" orientation="right" {...Y_AXIS} width={58} tickFormatter={(v: number) => formatInrCompact(v)} />
              )}
              {tooltip}
              {keys.map((k, i) => (
                <Line
                  key={k}
                  yAxisId={isPaise(k) && hasRight ? "p" : "n"}
                  type="monotone"
                  dataKey={k}
                  name={chart?.labels[k] ?? k}
                  stroke={color(k, i)}
                  strokeWidth={i > 0 ? 2 : 2.5}
                  strokeDasharray={i > 0 && !isPaise(k) ? "4 4" : undefined}
                  dot={false}
                  activeDot={{ r: 5, strokeWidth: 3, stroke: WHITE }}
                />
              ))}
            </LineChart>
          )}
        </ResponsiveContainer>
      </div>
    </ChartCard>
  );
}

export const findChart = (d: DashRoleView | undefined, id: string) => d?.charts.find((c) => c.id === id);
