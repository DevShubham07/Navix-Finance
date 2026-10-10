"use client";

import * as React from "react";
import { ResponsiveContainer } from "recharts";
import { cn } from "@/lib/utils";

/**
 * Chart primitives in the shadcn/ui "charts" pattern, on the Recharts we already ship.
 *
 * A chart declares its series once in a `ChartConfig`; `ChartContainer` turns each entry into a
 * `--color-<key>` CSS variable, so series are painted with `fill="var(--color-sales)"` and every
 * colour still resolves to a theme.css token. Axis/grid furniture is centralised in `chartAxis` /
 * `chartGrid` so every chart in the product reads the same: no axis lines, no tick marks, muted
 * labels, hairline horizontal grid only.
 */

export type ChartConfig = Record<string, { label: string; color: string }>;

/** Theme colours for series — always a token, never a hex. */
export const CHART_COLORS = {
  ember: "rgb(var(--c-chart-ember))",
  sun: "rgb(var(--c-chart-sun))",
  mint: "rgb(var(--c-chart-mint))",
  violet: "rgb(var(--c-chart-violet))",
  ink: "rgb(var(--c-chart-ink))",
  sky: "rgb(var(--c-chart-sky))",
  track: "rgb(var(--c-chart-track))",
  muted: "rgb(var(--c-muted))",
  line: "rgb(var(--c-line))",
} as const;

const ChartCtx = React.createContext<ChartConfig | null>(null);
export function useChartConfig(): ChartConfig {
  const c = React.useContext(ChartCtx);
  if (!c) throw new Error("useChartConfig must be used inside <ChartContainer>");
  return c;
}

export function ChartContainer({
  config,
  className,
  children,
}: {
  config: ChartConfig;
  className?: string;
  children: React.ReactElement;
}) {
  const style = Object.fromEntries(
    Object.entries(config).map(([k, v]) => [`--color-${k}`, v.color]),
  ) as React.CSSProperties;
  return (
    <ChartCtx.Provider value={config}>
      <div
        style={style}
        className={cn(
          "h-56 w-full text-[11px]",
          // Recharts furniture → theme
          "[&_.recharts-cartesian-axis-tick_text]:fill-[rgb(var(--c-muted))]",
          "[&_.recharts-cartesian-grid_line]:stroke-[rgb(var(--c-line))]",
          "[&_.recharts-surface]:outline-none",
          className,
        )}
      >
        <ResponsiveContainer width="100%" height="100%">
          {children}
        </ResponsiveContainer>
      </div>
    </ChartCtx.Provider>
  );
}

/** Shared axis props: no lines, no ticks, muted labels. Spread onto <XAxis>/<YAxis>. */
export const chartAxis = {
  axisLine: false,
  tickLine: false,
  tickMargin: 10,
  tick: { fontSize: 11 },
} as const;

/** Y axis: same furniture plus a width that never clips a 5-digit label. */
export const chartYAxis = { ...chartAxis, width: 44, tickMargin: 6 } as const;

/** Chart margins: flush left (the Y axis owns its own gutter). */
export const chartMargin = { top: 8, right: 8, left: 0, bottom: 0 } as const;

/** Shared grid props: hairline horizontals only. Spread onto <CartesianGrid>. */
export const chartGrid = { vertical: false, strokeDasharray: "0" } as const;

/** Hover band behind the hovered category. */
// A faint EMBER tint, never grey: grey series ("due", targets, tracks) would vanish into a grey band.
export const chartCursor = { fill: "rgb(var(--c-gold-500) / .06)", radius: 12 } as const;

interface TooltipEntry {
  dataKey?: string | number;
  name?: string;
  value?: number | string | null;
  color?: string;
  fill?: string;
  stroke?: string;
}

/**
 * The minimal tooltip: white card, hairline border, colour dot + label + value per series.
 * Use as `<Tooltip content={<ChartTooltipContent format={...} />} />`.
 */
export function ChartTooltipContent({
  active,
  payload,
  label,
  format = (v) => (typeof v === "number" ? v.toLocaleString("en-IN") : String(v ?? "—")),
  labelFormat,
}: {
  active?: boolean;
  payload?: TooltipEntry[];
  label?: string | number;
  format?: (v: number | string | null | undefined, key: string) => string;
  labelFormat?: (label: string | number | undefined) => string;
}) {
  const config = React.useContext(ChartCtx);
  if (!active || !payload?.length) return null;
  return (
    <div className="min-w-[9rem] rounded-xl border border-line bg-paper px-3 py-2 text-[11px] shadow-md" role="status">
      <p className="m-0 mb-1.5 font-semibold text-ink">{labelFormat ? labelFormat(label) : label}</p>
      <ul className="m-0 list-none space-y-1 p-0">
        {payload.map((p) => {
          const key = String(p.dataKey);
          return (
            <li key={key} className="flex items-center justify-between gap-4">
              <span className="flex items-center gap-1.5 text-muted">
                <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: p.color ?? p.fill ?? p.stroke }} />
                {config?.[key]?.label ?? p.name ?? key}
              </span>
              <span className="font-semibold tabular-nums text-ink">{format(p.value, key)}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Inline legend row: dot + label per configured series. */
export function ChartLegend({ config, className }: { config: ChartConfig; className?: string }) {
  return (
    <ul className={cn("m-0 flex list-none flex-wrap items-center gap-x-4 gap-y-1 p-0 text-[11px] text-muted", className)}>
      {Object.entries(config).map(([k, v]) => (
        <li key={k} className="flex items-center gap-1.5">
          <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: v.color }} />
          {v.label}
        </li>
      ))}
    </ul>
  );
}
