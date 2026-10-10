"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { paiseToINR, type DashKpi, type DashMetric, type DashTone } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { DELTA_STYLE } from "./chart-parts";
import { TONE_SOLID, TONE_TEXT, WHITE } from "./colors";
import { useCountUp } from "./use-count-up";

/** Percent change of `cur` over `prev`; null when there is no previous figure to compare with. */
export function deltaPct(cur: number | null | undefined, prev: number | null | undefined): number | null {
  if (cur == null || prev == null || prev === 0) return null;
  return ((cur - prev) / prev) * 100;
}

/**
 * ▲/▼ chip against the previous period: a solid mint pill when the move is good, red when it is
 * bad, grey when flat. Direction is a glyph, never colour alone.
 */
export function DeltaChip({
  pct,
  className,
  invert = false,
}: {
  pct: number | null;
  className?: string;
  /** Pass true when "down" is the good direction (rejections, deficit): flips the pill colour and the hint. */
  invert?: boolean;
}) {
  if (pct == null) return null;
  const up = pct > 0;
  const flat = Math.abs(pct) < 0.05;
  const glyph = flat ? "●" : up ? "▲" : "▼";
  const style = flat ? DELTA_STYLE.flat : up !== invert ? DELTA_STYLE.good : DELTA_STYLE.bad;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-[10px] font-semibold tabular-nums",
        className,
      )}
      style={style}
      title={`${flat ? "No change" : up ? "Up" : "Down"} ${Math.abs(pct).toFixed(1)}% vs previous period${invert ? " (lower is better)" : ""}`}
    >
      <span aria-hidden className="text-[8px]">
        {glyph}
      </span>
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
  /** True when a fall is the good direction (rejections): the delta pill reads mint on a fall. */
  lowerIsBetter?: boolean;
  className?: string;
}

const pctOf = (part: number, whole: number) => (whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "—");

/**
 * A KPI card in the kit's light-panel look (`.surface`): muted title, the figure in the condensed
 * display face, a delta pill and the Fresh / Re-loan split. The tone shows only as the icon in its
 * round pill and in the split bar; a `navy` tone renders the black feature card (`.surface-dark`)
 * instead. The whole card is ONE button (click / Enter opens the records drawer for `kpi.metric`);
 * a hover/focus popover carries the exact Fresh / Re-loan / previous-period figures. The popover is
 * a sibling of the button, not a child, so it is valid markup.
 */
export function KpiCard({
  title,
  tone,
  icon: Icon,
  kpi,
  mode = "count",
  onOpen,
  shareLabel,
  lowerIsBetter = false,
  className,
}: KpiCardProps) {
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

  const dark = tone === "navy";
  const soft = dark ? "text-white/65" : "text-slate";
  const strong = dark ? "text-white" : "text-ink";
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
        className={cn(dark ? "surface-dark" : "surface", CARD_BUTTON)}
      >
        <span className="flex items-start justify-between gap-2">
          <span className={cn("pt-1 text-xs font-medium", soft)}>{title}</span>
          <CardIcon icon={Icon} tone={tone} dark={dark} />
        </span>

        <span className="mt-3 flex flex-wrap items-start gap-2">
          <span aria-hidden className={cn("figure-display text-[2.2rem]", strong)}>
            {shown}
          </span>
          <DeltaChip pct={delta} invert={lowerIsBetter} className="mt-0.5" />
        </span>

        <span className="mt-4 grid grid-cols-2 gap-x-3 text-xs">
          <span>
            <span className={cn("block", soft)}>Fresh</span>
            <span className={cn("font-semibold tabular-nums", strong)}>{fmt(freshVal)}</span>
            <span className={cn("ml-1", soft)}>{pctOf(freshVal, whole)}</span>
          </span>
          <span className="text-right">
            <span className={cn("block", soft)}>Re-loan</span>
            <span className={cn("font-semibold tabular-nums", strong)}>{fmt(reloanVal)}</span>
            <span className={cn("ml-1", soft)}>{pctOf(reloanVal, whole)}</span>
          </span>
        </span>

        <SplitBar freshShare={freshShare} hasData={whole > 0} tone={tone} dark={dark} />
      </button>

      {open && (
        <CardPopover title={title}>
          <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <dt className="text-slate">Fresh</dt>
            <dd className="m-0 text-right font-medium tabular-nums">
              {kpi.fresh.toLocaleString("en-IN")} · {paiseToINR(kpi.freshPaise)}
            </dd>
            <dt className="text-slate">Re-loan</dt>
            <dd className="m-0 text-right font-medium tabular-nums">
              {kpi.reloan.toLocaleString("en-IN")} · {paiseToINR(kpi.reloanPaise)}
            </dd>
            <dt className="text-slate">Previous period</dt>
            <dd className="m-0 text-right font-medium tabular-nums">
              {kpi.previousCount == null ? "—" : kpi.previousCount.toLocaleString("en-IN")} ·{" "}
              {paiseToINR(kpi.previousAmountPaise)}
            </dd>
            {shareLabel && (
              <>
                <dt className="text-slate">Share</dt>
                <dd className="m-0 text-right font-medium">{shareLabel}</dd>
              </>
            )}
          </dl>
        </CardPopover>
      )}
    </div>
  );
}

/* ---- pieces shared with PositionCard ---------------------------------------------------- */

/** The card-as-button furniture on top of `.surface` / `.surface-dark`: lift on hover, ember focus ring. */
export const CARD_BUTTON = cn(
  "group relative flex h-full w-full flex-col overflow-hidden p-5 text-left",
  "transition duration-200 hover:-translate-y-0.5 hover:shadow-md",
  "focus:outline-none focus-visible:ring-2 focus-visible:ring-gold/50 focus-visible:ring-offset-2 focus-visible:ring-offset-ivory",
);

/**
 * The tone's only appearance on a light card: its icon, in a round white pill. On the black card
 * the pill is a translucent white disc.
 */
export function CardIcon({ icon: Icon, tone, dark }: { icon: LucideIcon; tone: DashTone; dark: boolean }) {
  return (
    <span
      aria-hidden
      className={cn(
        "grid h-9 w-9 shrink-0 place-items-center rounded-full",
        dark ? "bg-white/10 text-white" : "border border-line bg-paper shadow-pill",
      )}
      style={dark ? undefined : { color: TONE_TEXT[tone] }}
    >
      <Icon size={15} strokeWidth={1.9} />
    </span>
  );
}

/** Hover / focus popover under a card: white, hairline border, soft shadow, ink text. */
export function CardPopover({ id, title, children }: { id?: string; title: string; children: React.ReactNode }) {
  return (
    <div
      role="tooltip"
      id={id}
      className="pointer-events-none absolute inset-x-2 top-full z-30 mt-2 rounded-xl border border-line bg-paper p-3 text-[11px] text-ink shadow-md"
    >
      <p className="m-0 mb-1.5 font-semibold text-ink">{title}</p>
      {children}
      <p className="m-0 mt-1.5 text-[10px] text-slate">Click to view records</p>
    </div>
  );
}

/** Fresh / Re-loan split as a capsule bar: fresh in the tone colour, re-loan the same tone at a third. */
function SplitBar({ freshShare, hasData, tone, dark }: { freshShare: number; hasData: boolean; tone: DashTone; dark: boolean }) {
  const fill = dark ? WHITE : TONE_SOLID[tone];
  return (
    <span
      aria-hidden
      className={cn("mt-3 flex h-1.5 w-full gap-0.5 overflow-hidden rounded-full", dark ? "bg-white/15" : "bg-chart-track")}
    >
      {hasData && freshShare > 0 && (
        <span className="h-full rounded-full" style={{ width: `${freshShare}%`, background: fill }} />
      )}
      {hasData && freshShare < 100 && (
        <span className="h-full flex-1 rounded-full" style={{ background: fill, opacity: 0.35 }} />
      )}
    </span>
  );
}
