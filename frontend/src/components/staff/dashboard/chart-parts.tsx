import * as React from "react";
import { CHART, PCT_TONE_COLOR, PCT_TONE_TINT, TEXT, TONE_TINT, fmtPct, pctTone } from "./colors";

/** Vertical gradient for bar fills: full colour on top fading to 70% at the base. */
export function BarGradient({ id, color }: { id: string; color: string }) {
  return (
    <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stopColor={color} stopOpacity={1} />
      <stop offset="100%" stopColor={color} stopOpacity={0.7} />
    </linearGradient>
  );
}

/** Navy/6% hover band behind the hovered category. */
export const CURSOR = { fill: CHART.cursor } as const;

export const AXIS_TICK = { fontSize: 11, fill: TEXT.axis } as const;
export const GRID_STROKE = CHART.grid;

/** Recharts hands onClick the bar/point props; the row the user clicked is `.payload`. */
export function payloadOf<T>(d: unknown): T | undefined {
  return (d as { payload?: T } | null | undefined)?.payload;
}

/** From a chart-level onClick state: the first active row. */
export function activeRow<T>(state: unknown): T | undefined {
  const rows = (state as { activePayload?: { payload?: T }[] } | null | undefined)?.activePayload;
  return rows?.[0]?.payload;
}

/** ▲/▼ chip for use on a WHITE surface (KpiCard's own chip is white-on-colour). */
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
  const color = good == null ? TEXT.axis : good ? TEXT.good : TEXT.badDeep;
  const bg = good == null ? PCT_TONE_TINT.none : good ? TONE_TINT.emerald : TONE_TINT.red;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${className ?? ""}`}
      style={{ color, background: bg }}
      title={`${up ? "Up" : "Down"} ${Math.abs(pct).toFixed(1)}% vs previous period`}
    >
      <span aria-hidden>{flat ? "●" : up ? "▲" : "▼"}</span>
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
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-bold tabular-nums ${className ?? ""}`}
      style={{ color: PCT_TONE_COLOR[pctTone(ratio)], background: PCT_TONE_TINT[pctTone(ratio)] }}
    >
      {fmtPct(ratio, digits)}
    </span>
  );
}

/** Thin horizontal progress bar in a tone colour. */
export function ProgressBar({ ratio, color, className }: { ratio: number | null | undefined; color: string; className?: string }) {
  const w = ratio == null || !Number.isFinite(ratio) ? 0 : Math.max(0, Math.min(1, ratio)) * 100;
  return (
    <div className={`h-2 w-full overflow-hidden rounded-full bg-grey-100 ${className ?? ""}`} aria-hidden>
      <div className="h-full rounded-full transition-all" style={{ width: `${w}%`, background: color }} />
    </div>
  );
}
