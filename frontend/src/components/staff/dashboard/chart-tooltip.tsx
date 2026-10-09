"use client";

import * as React from "react";
import { paiseToINR } from "@/lib/api/applications";
import { fmtPct } from "./colors";
import { fmtAxisLabel, nf } from "./fmt";

export type TooltipKind = "count" | "paise" | "pct" | "ratio";

export interface TooltipLine {
  label: string;
  value: string;
  color?: string;
}

interface Entry {
  dataKey?: string | number;
  name?: string;
  value?: number | string | null;
  color?: string;
  stroke?: string;
  fill?: string;
  payload?: Record<string, unknown>;
}

export function formatTooltipValue(kind: TooltipKind, v: unknown): string {
  if (v == null || v === "") return "—";
  const n = Number(v);
  if (!Number.isFinite(n)) return String(v);
  switch (kind) {
    case "paise":
      return paiseToINR(n);
    case "pct":
      return `${n.toFixed(1)}%`;
    case "ratio":
      return fmtPct(n);
    default:
      return nf(n);
  }
}

export interface ChartTooltipProps {
  /** Injected by Recharts. */
  active?: boolean;
  payload?: Entry[];
  label?: string | number;
  /** How each series (by dataKey) is formatted. Default: count. */
  kinds?: Record<string, TooltipKind>;
  /** Overrides the series names Recharts supplies. */
  labels?: Record<string, string>;
  /** Hide series by key (helper series such as a cumulative line you show as a derived line instead). */
  hide?: string[];
  /** Title override. Default: the x value, dates pretty-printed. */
  title?: (label: string | number | undefined, row: Record<string, unknown> | undefined) => string;
  /** Derived lines under the series: share %, target, deficit, cumulative total ... */
  extra?: (row: Record<string, unknown>) => TooltipLine[];
  /** Append a "Total" line summing every shown series (only meaningful when they share a unit). */
  total?: TooltipKind;
}

/**
 * The one Recharts tooltip for every dashboard chart: navy card, white text, the date or month as
 * the title, one row per series (colour dot, label, exact value) and optional derived lines.
 * Pass as `<Tooltip content={<ChartTooltip kinds={...} />} />`.
 */
export function ChartTooltip({
  active,
  payload,
  label,
  kinds,
  labels,
  hide,
  title,
  extra,
  total,
}: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;
  const row = payload[0]?.payload;
  const shown = payload.filter((p) => p.dataKey != null && !(hide ?? []).includes(String(p.dataKey)));
  const heading = title ? title(label, row) : fmtAxisLabel(label ?? (row?.label as string | undefined));
  const sum = total ? shown.reduce((s, p) => s + (Number(p.value) || 0), 0) : null;

  return (
    <div className="min-w-[10rem] rounded-lg bg-navy px-3 py-2.5 text-xs text-white shadow-xl" role="status">
      <p className="m-0 mb-1.5 font-semibold">{heading}</p>
      <ul className="m-0 list-none space-y-1 p-0">
        {shown.map((p) => {
          const key = String(p.dataKey);
          const color = p.color ?? p.stroke ?? p.fill;
          return (
            <li key={key} className="flex items-center justify-between gap-4">
              <span className="flex items-center gap-1.5 text-white/80">
                <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: color }} />
                {labels?.[key] ?? p.name ?? key}
              </span>
              <span className="font-semibold tabular-nums">{formatTooltipValue(kinds?.[key] ?? "count", p.value)}</span>
            </li>
          );
        })}
        {sum != null && total && shown.length > 1 && (
          <li className="flex items-center justify-between gap-4 border-t border-white/20 pt-1">
            <span className="text-white/80">Total</span>
            <span className="font-semibold tabular-nums">{formatTooltipValue(total, sum)}</span>
          </li>
        )}
        {row &&
          extra?.(row).map((l) => (
            <li key={l.label} className="flex items-center justify-between gap-4 text-white/90">
              <span className="flex items-center gap-1.5 text-white/70">
                {l.color && <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: l.color }} />}
                {l.label}
              </span>
              <span className="tabular-nums">{l.value}</span>
            </li>
          ))}
      </ul>
    </div>
  );
}
