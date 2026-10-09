"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { paiseToINR, type DashKpi, type DashMetric, type DashTone } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { CARD_HIGHLIGHT, gradientCss } from "./colors";
import { useCountUp } from "./use-count-up";

/** Percent change of `cur` over `prev`; null when there is no previous figure to compare with. */
export function deltaPct(cur: number | null | undefined, prev: number | null | undefined): number | null {
  if (cur == null || prev == null || prev === 0) return null;
  return ((cur - prev) / prev) * 100;
}

/** ▲/▼ chip against the previous period. Direction is a glyph, never colour alone. */
export function DeltaChip({
  pct,
  className,
  invert = false,
}: {
  pct: number | null;
  className?: string;
  /** Pass true when "down" is the good direction (rejections, deficit). Only changes the aria hint. */
  invert?: boolean;
}) {
  if (pct == null) return null;
  const up = pct > 0;
  const flat = Math.abs(pct) < 0.05;
  const glyph = flat ? "●" : up ? "▲" : "▼";
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-full bg-white/20 px-2 py-0.5 text-[11px] font-semibold text-white",
        className,
      )}
      title={`${flat ? "No change" : up ? "Up" : "Down"} ${Math.abs(pct).toFixed(1)}% vs previous period${invert ? " (lower is better)" : ""}`}
    >
      <span aria-hidden>{glyph}</span>
      {Math.abs(pct).toFixed(1)}%
      <span className="sr-only"> vs previous period</span>
    </span>
  );
}

export interface KpiCardProps {
  title: string;
  tone: DashTone;
  icon: LucideIcon;
  kpi: DashKpi;
  /** What the big number is: the application count, or the rupee amount. */
  mode?: "count" | "money";
  onOpen: (metric: DashMetric) => void;
  /** Extra line in the hover popover, e.g. "12.5% of all applications". */
  shareLabel?: string;
  className?: string;
}

const pctOf = (part: number, whole: number) => (whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "—");

/**
 * A vivid gradient KPI card. The whole card is ONE button (click / Enter opens the records drawer
 * for `kpi.metric`); a hover/focus popover carries the exact Fresh / Re-loan / previous-period
 * figures. The popover is a sibling of the button, not a child, so it is valid markup.
 */
export function KpiCard({ title, tone, icon: Icon, kpi, mode = "count", onOpen, shareLabel, className }: KpiCardProps) {
  const money = mode === "money";
  const main = money ? (kpi.amountPaise ?? 0) : kpi.count;
  const animated = useCountUp(main);
  const shown = money ? paiseToINR(Math.round(animated)) : Math.round(animated).toLocaleString("en-IN");
  const finalText = money ? paiseToINR(main) : main.toLocaleString("en-IN");

  const prev = money ? kpi.previousAmountPaise : kpi.previousCount;
  const delta = deltaPct(main, prev);

  const freshVal = money ? (kpi.freshPaise ?? 0) : kpi.fresh;
  const reloanVal = money ? (kpi.reloanPaise ?? 0) : kpi.reloan;
  const whole = freshVal + reloanVal;
  const fmt = (n: number) => (money ? paiseToINR(n) : n.toLocaleString("en-IN"));
  const freshShare = whole > 0 ? (freshVal / whole) * 100 : 0;

  const [open, setOpen] = React.useState(false);
  const label = `${title}: ${finalText}. Fresh ${fmt(freshVal)}, re-loan ${fmt(reloanVal)}. Open the records behind this number.`;

  return (
    <div
      className={cn("relative", className)}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      <button
        type="button"
        aria-label={label}
        onClick={() => onOpen(kpi.metric)}
        style={{ backgroundImage: gradientCss(tone) }}
        className={cn(
          "group relative flex h-full w-full flex-col overflow-hidden rounded-xl p-4 text-left text-white shadow-md",
          "transition duration-200 hover:-translate-y-0.5 hover:shadow-xl hover:ring-2 hover:ring-white/40",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-white/70 focus-visible:ring-offset-2 focus-visible:ring-offset-navy",
        )}
      >
        {/* soft highlight + watermark = the "vivid" look */}
        <span
          aria-hidden
          className="pointer-events-none absolute -right-10 -top-10 h-36 w-36 rounded-full"
          style={{ background: CARD_HIGHLIGHT }}
        />
        <Icon aria-hidden className="pointer-events-none absolute -bottom-3 -right-2 h-24 w-24 text-white/10" />

        <span className="relative flex items-start justify-between gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-white/90">{title}</span>
          <Icon aria-hidden size={16} className="shrink-0 text-white" />
        </span>

        <span className="relative mt-2 flex flex-wrap items-center gap-2">
          <span aria-hidden className="font-serif text-2xl font-bold leading-none tabular-nums sm:text-3xl">
            {shown}
          </span>
          <DeltaChip pct={delta} />
        </span>

        <span className="relative mt-3 grid grid-cols-2 gap-x-3 text-xs">
          <span>
            <span className="block text-white/80">Fresh</span>
            <span className="font-semibold tabular-nums">{fmt(freshVal)}</span>
            <span className="ml-1 text-white/70">{pctOf(freshVal, whole)}</span>
          </span>
          <span className="text-right">
            <span className="block text-white/80">Re-loan</span>
            <span className="font-semibold tabular-nums">{fmt(reloanVal)}</span>
            <span className="ml-1 text-white/70">{pctOf(reloanVal, whole)}</span>
          </span>
        </span>

        <span aria-hidden className="relative mt-2 flex h-1.5 w-full overflow-hidden rounded-full bg-white/20">
          <span className="h-full bg-white/90" style={{ width: `${freshShare}%` }} />
          <span className="h-full bg-white/40" style={{ width: `${whole > 0 ? 100 - freshShare : 0}%` }} />
        </span>
      </button>

      {open && (
        <div
          role="tooltip"
          className="pointer-events-none absolute inset-x-2 top-full z-30 mt-2 rounded-lg bg-navy p-3 text-xs text-white shadow-xl"
        >
          <p className="m-0 mb-1.5 font-semibold">{title}</p>
          <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <dt className="text-white/70">Fresh</dt>
            <dd className="m-0 text-right tabular-nums">
              {kpi.fresh.toLocaleString("en-IN")} · {paiseToINR(kpi.freshPaise)}
            </dd>
            <dt className="text-white/70">Re-loan</dt>
            <dd className="m-0 text-right tabular-nums">
              {kpi.reloan.toLocaleString("en-IN")} · {paiseToINR(kpi.reloanPaise)}
            </dd>
            <dt className="text-white/70">Previous period</dt>
            <dd className="m-0 text-right tabular-nums">
              {kpi.previousCount == null ? "—" : kpi.previousCount.toLocaleString("en-IN")} ·{" "}
              {paiseToINR(kpi.previousAmountPaise)}
            </dd>
            {shareLabel && (
              <>
                <dt className="text-white/70">Share</dt>
                <dd className="m-0 text-right">{shareLabel}</dd>
              </>
            )}
          </dl>
          <p className="m-0 mt-1.5 text-[10px] text-white/60">Click to view records</p>
        </div>
      )}
    </div>
  );
}
