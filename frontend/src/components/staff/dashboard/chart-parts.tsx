import * as React from "react";
import { chartAxis, chartGrid, chartYAxis } from "@/components/kit";
import { CHART, PCT_TONE_COLOR, PCT_TONE_TINT, TEXT, fmtPct, pctTone } from "./colors";

/**
 * Bar fill for a series: solid colour with the faintest fade toward the base. Kept as a gradient
 * def so existing `fill="url(#id)"` callers keep working; new charts should pass the colour directly
 * (the tooltip's series dot reads the fill, and a `url(#…)` fill has no colour to show).
 */
export function BarGradient({ id, color }: { id: string; color: string }) {
  return (
    <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stopColor={color} stopOpacity={1} />
      <stop offset="100%" stopColor={color} stopOpacity={0.88} />
    </linearGradient>
  );
}

/** Barely-there rounded hover band behind the hovered category. */
export const CURSOR = { fill: CHART.cursor, radius: 12 } as const;

export const AXIS_TICK = { fontSize: 11, fill: TEXT.axis } as const;
export const GRID_STROKE = CHART.grid;

/**
 * Chart furniture shared with the kit (`@/components/kit` chartAxis / chartGrid): no axis lines, no
 * tick marks, muted 11px labels, hairline horizontal grid only. Spread onto the Recharts element,
 * then add the chart's own props (`dataKey`, `width`, `tickFormatter` …) after it.
 */
export const X_AXIS = { ...chartAxis, tick: AXIS_TICK } as const;
export const Y_AXIS = { ...chartYAxis, tick: AXIS_TICK } as const;
export const GRID = { ...chartGrid, stroke: GRID_STROKE } as const;

/** Bar geometry: fully rounded capsules that never grow fat on a sparse chart. */
export const BAR = { radius: [8, 8, 8, 8] as [number, number, number, number], maxBarSize: 34 } as const;

/** Value label above a bar / point: small, ink, medium weight. */
export const VALUE_LABEL = { fontSize: 10, fontWeight: 600, fill: TEXT.axis } as const;

/** Recharts hands onClick the bar/point props; the row the user clicked is `.payload`. */
export function payloadOf<T>(d: unknown): T | undefined {
  return (d as { payload?: T } | null | undefined)?.payload;
}

/** From a chart-level onClick state: the first active row. */
export function activeRow<T>(state: unknown): T | undefined {
  const rows = (state as { activePayload?: { payload?: T }[] } | null | undefined)?.activePayload;
  return rows?.[0]?.payload;
}

/** Delta pill styles, shared with KpiCard's DeltaChip: solid mint up, solid red down, grey flat. */
export const DELTA_STYLE = {
  good: { color: "#093D25", background: "#5CD398" },
  bad: { color: "#FFFFFF", background: TEXT.bad },
  flat: { color: "#4A4D52", background: PCT_TONE_TINT.none },
} as const;

/** ▲/▼ chip against the previous period: a mint (good) or red (bad) pill. Direction is a glyph too. */
export function DeltaPill({
  pct,
  goodWhenUp = true,
  className,
}: {
  pct: number | null | undefined;
  goodWhenUp?: boolean;
  className?: string;
}) {
  if (pct == null || !Number.isFinite(pct)) return null;
  const flat = Math.abs(pct) < 0.05;
  const up = pct > 0;
  const good = flat ? null : up === goodWhenUp;
  const style = good == null ? DELTA_STYLE.flat : good ? DELTA_STYLE.good : DELTA_STYLE.bad;
  return (
    <span
      className={`inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-[10px] font-semibold tabular-nums ${className ?? ""}`}
      style={style}
      title={`${up ? "Up" : "Down"} ${Math.abs(pct).toFixed(1)}% vs previous period`}
    >
      <span aria-hidden className="text-[8px]">{flat ? "●" : up ? "▲" : "▼"}</span>
      {Math.abs(pct).toFixed(1)}%
    </span>
  );
}

/** Coloured percentage chip, tone from the shared thresholds. */
export function PctChip({
  ratio,
  digits = 1,
  className,
}: {
  ratio: number | null | undefined;
  digits?: number;
  className?: string;
}) {
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold tabular-nums ${className ?? ""}`}
      style={{ color: PCT_TONE_COLOR[pctTone(ratio)], background: PCT_TONE_TINT[pctTone(ratio)] }}
    >
      {fmtPct(ratio, digits)}
    </span>
  );
}

/** Thin capsule progress bar in a tone colour on the pale chart track. */
export function ProgressBar({ ratio, color, className }: { ratio: number | null | undefined; color: string; className?: string }) {
  const w = ratio == null || !Number.isFinite(ratio) ? 0 : Math.max(0, Math.min(1, ratio)) * 100;
  return (
    <div className={`h-1.5 w-full overflow-hidden rounded-full bg-chart-track ${className ?? ""}`} aria-hidden>
      <div className="h-full rounded-full transition-all" style={{ width: `${w}%`, background: color }} />
    </div>
  );
}
