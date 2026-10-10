"use client";

import * as React from "react";
import { cn } from "@/lib/utils";
import { MEDAL, NAVY } from "./colors";

export const chartBox = "h-64 min-h-[240px] w-full";
export const selectCls = "h-8 cursor-pointer rounded-full border border-line bg-paper px-3 text-xs font-medium text-ink shadow-pill";

/** Pill-group toggle on a pale track (ink pill when on) used by every dashboard widget that switches mode. */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
  colors,
}: {
  value: T;
  options: readonly (readonly [T, string])[];
  onChange: (v: T) => void;
  label: string;
  /** Optional fill colour of the active pill, per option. */
  colors?: Partial<Record<T, string>>;
}) {
  return (
    <div role="group" aria-label={label} className="flex gap-0.5 rounded-full border border-line bg-grey-100 p-0.5">
      {options.map(([k, l]) => (
        <button
          key={k}
          type="button"
          aria-pressed={value === k}
          onClick={() => onChange(k)}
          className={cn("rounded-full px-3 py-1 text-[11px] font-medium transition", value === k ? "text-white shadow-pill" : "text-slate hover:text-ink")}
          style={value === k ? { background: colors?.[k] ?? NAVY } : undefined}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

/**
 * Round white icon pill (the kit's `.icon-pill` look) with the icon drawn in `color` — pass a deep
 * TONE_TEXT shade so the glyph keeps 3:1 against white. Employee Snapshot rows, stat cards.
 */
export function IconSquare({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <span
      aria-hidden
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-line bg-paper shadow-pill"
      style={{ color }}
    >
      {children}
    </span>
  );
}

const RANK_COLORS = [MEDAL[1], MEDAL[2], MEDAL[3]];
/** 1-based rank badge: gold / silver / bronze discs (ink figure) for the podium, a pale disc after. */
export function RankBadge({ rank }: { rank: number }) {
  return (
    <span
      className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-grey-100 text-[11px] font-semibold text-ink"
      style={RANK_COLORS[rank - 1] ? { background: RANK_COLORS[rank - 1] } : undefined}
    >
      {rank}
    </span>
  );
}

/** Small stat chip: coloured dot, label, bold value. */
export function StatChip({ color, label, value, tint }: { color: string; label: string; value: React.ReactNode; tint?: string }) {
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full border border-line bg-paper px-2.5 py-1 text-[11px] font-medium text-ink"
      style={tint ? { background: tint, borderColor: "transparent" } : undefined}
    >
      <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: color }} />
      {label} <b className="font-semibold tabular-nums" style={{ color }}>{value}</b>
    </span>
  );
}
