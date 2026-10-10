"use client";

import * as React from "react";
import { Info } from "lucide-react";
import { cn } from "@/lib/utils";
import { CHART_COLORS } from "./chart";

/**
 * Reference visualisations, built from DOM (not SVG) so they stay pixel-crisp, theme-driven and
 * accessible. Each one takes plain data and carries its own screen-reader summary.
 *
 *   <CapsuleBarChart>  "Overall Sales": staggered white capsules, ember bar inside, "+36%" cap, month pills
 *   <SegmentBars>      "Source": labelled rows of rounded, gapped segments
 *   <DotMatrix>        "Total Transactions": stacked dot columns
 *   <GoalProgress>     "Gross Revenue": goal label + days left + mint bar on an ink track
 *   <DayStrip>         "Reminders": compact day chips with one selected
 *   <AvatarStack>      overlapping initials avatars (+ optional add button)
 *   <PagerDots>        vertical carousel dots
 */

/* ------------------------------------------------------------------ Capsule bar chart */

export interface CapsuleDatum {
  label: string;
  value: number;
  /** Change vs. previous point, shown as the capsule cap ("+ 36%"). */
  delta?: number | null;
}

export function CapsuleBarChart({
  data,
  format = (v) => v.toLocaleString("en-IN"),
  height = 190,
  color = CHART_COLORS.ember,
  ariaLabel,
  className,
}: {
  data: CapsuleDatum[];
  format?: (v: number) => string;
  height?: number;
  color?: string;
  ariaLabel: string;
  className?: string;
}) {
  const max = Math.max(1, ...data.map((d) => d.value));
  const [hover, setHover] = React.useState<number | null>(null);
  return (
    <figure className={cn("m-0 -mx-1 overflow-x-auto px-1 pt-10 [scrollbar-width:none]", className)} aria-label={ariaLabel}>
      {/* Never squash: below ~52px per capsule the chart scrolls sideways (the reference crops too). */}
      <div style={{ minWidth: data.length * 52 }}>
      <div className="flex items-end gap-2 sm:gap-3" style={{ height }} role="list">
        {data.map((d, i) => {
          const r = d.value / max;
          // Capsule height follows the value; bar fills ~55-75% of it — the reference's stagger.
          const capsule = 0.42 + 0.58 * r;
          const bar = 0.4 + 0.25 * r;
          const active = hover === i;
          return (
            <div
              key={d.label}
              role="listitem"
              tabIndex={0}
              aria-label={`${d.label}: ${format(d.value)}${d.delta != null ? `, ${d.delta >= 0 ? "up" : "down"} ${Math.abs(d.delta)}%` : ""}`}
              onMouseEnter={() => setHover(i)}
              onMouseLeave={() => setHover(null)}
              onFocus={() => setHover(i)}
              onBlur={() => setHover(null)}
              className="group relative flex h-full min-w-0 flex-1 flex-col justify-end outline-none"
            >
              <div
                className={cn(
                  "relative flex flex-col justify-between rounded-[18px] border border-line bg-paper p-1.5 shadow-xs transition-all duration-300",
                  active && "-translate-y-1 shadow-base",
                )}
                style={{ height: `${capsule * 100}%` }}
              >
                <span className="block whitespace-nowrap pt-2 text-center text-[10.5px] font-medium tabular-nums text-ink">
                  {d.delta != null ? `${d.delta >= 0 ? "+" : "−"}${Math.abs(d.delta)}%` : ""}
                </span>
                <span
                  className="block w-full origin-bottom animate-grow-y rounded-[13px] transition-[filter] duration-300"
                  style={{ height: `${bar * 100}%`, background: color, filter: active ? "saturate(1.15)" : undefined, animationDelay: `${i * 55}ms` }}
                />
              </div>
              {active && (
                <span className="pointer-events-none absolute -top-9 left-1/2 z-10 -translate-x-1/2 whitespace-nowrap rounded-full bg-navy px-2.5 py-1 text-[10.5px] font-semibold text-white shadow-md">
                  {format(d.value)}
                </span>
              )}
            </div>
          );
        })}
      </div>
      <div className="mt-3 flex gap-2 sm:gap-3" aria-hidden>
        {data.map((d, i) => (
          <span
            key={d.label}
            className={cn(
              "flex-1 truncate rounded-full py-1 text-center text-[10px] transition-colors",
              hover === i ? "bg-navy text-white" : "bg-grey-100 text-muted",
            )}
          >
            {d.label}
          </span>
        ))}
      </div>
      </div>
    </figure>
  );
}

/* ------------------------------------------------------------------ Segment bars */

export interface SegmentRow {
  label: string;
  hint?: string;
  segments: { label: string; value: number; color: string }[];
}

export function SegmentBars({
  rows,
  format = (v) => v.toLocaleString("en-IN"),
  scale = "shared",
  className,
}: {
  rows: SegmentRow[];
  format?: (v: number) => string;
  /** shared = row lengths compare against each other (same unit); row = every row is full width
   *  (rows in different units, e.g. a count row beside a rupee row). */
  scale?: "shared" | "row";
  className?: string;
}) {
  const max = Math.max(1, ...rows.map((r) => r.segments.reduce((s, x) => s + x.value, 0)));
  return (
    <div className={cn("space-y-5 border-l border-dashed border-line-2 pl-4", className)} style={{ borderColor: "rgb(var(--c-line-2))" }}>
      {rows.map((row) => {
        const total = row.segments.reduce((s, x) => s + x.value, 0);
        return (
          <div key={row.label}>
            <p className="m-0 mb-2 flex items-center gap-1.5 text-[11px] text-muted" title={row.hint}>
              <Info size={12} aria-hidden />
              {row.label}
            </p>
            <div className="flex h-8 gap-1.5" style={{ width: scale === "row" ? "100%" : `${(total / max) * 100}%` }}>
              {row.segments.map((s) => (
                <span
                  key={s.label}
                  title={`${s.label}: ${format(s.value)}`}
                  className="block h-full rounded-[10px] transition-transform duration-200 hover:-translate-y-0.5"
                  style={{ flexGrow: s.value, flexBasis: 0, background: s.color }}
                />
              ))}
            </div>
            <span className="sr-only">
              {row.segments.map((s) => `${s.label} ${format(s.value)}`).join(", ")}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ Dot matrix */

export function DotMatrix({
  columns,
  max = 3,
  color = CHART_COLORS.ember,
  ariaLabel,
  groupEvery = 2,
  className,
}: {
  /** Value per column, 0..max dots. */
  columns: number[];
  max?: number;
  color?: string;
  ariaLabel: string;
  /** Visually group columns in clusters (the reference groups pairs/triples). */
  groupEvery?: number;
  className?: string;
}) {
  return (
    <div className={cn("flex items-end gap-1.5", className)} role="img" aria-label={ariaLabel}>
      {columns.map((n, i) => (
        <div key={i} className={cn("flex flex-col-reverse gap-1.5", groupEvery && i > 0 && i % groupEvery === 0 && "ml-3")}>
          {Array.from({ length: max }, (_, k) => (
            <span
              key={k}
              className={cn("block h-[15px] w-[15px] rounded-[5px]", k < n && "animate-pop")}
              style={{ background: color, opacity: k < n ? 1 : 0, animationDelay: `${300 + i * 35 + k * 60}ms` }}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ Goal progress */

export function GoalProgress({
  ratio,
  label = "Goal",
  trailing,
  tone = "dark",
  className,
}: {
  ratio: number;
  label?: string;
  trailing?: React.ReactNode;
  /** dark = mint on an ink track (reference); light = ember on a grey track; inverse = mint on a
   *  translucent white track, for use INSIDE a dark card. */
  tone?: "dark" | "light" | "inverse";
  className?: string;
}) {
  const w = Math.max(0, Math.min(1, ratio)) * 100;
  return (
    <div className={className}>
      <div className={cn("mb-1.5 flex justify-between gap-3 text-[10px]", tone === "inverse" ? "text-white/60" : "text-muted")}>
        <span>{label}</span>
        {trailing && <span>{trailing}</span>}
      </div>
      <div
        className={cn("h-4 overflow-hidden rounded-full p-[3px]", tone === "dark" ? "bg-navy" : tone === "inverse" ? "bg-white/15" : "bg-grey-200")}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(w)}
        aria-label={label}
      >
        <div
          className="h-full rounded-full transition-[width] duration-700"
          style={{ width: `${w}%`, background: tone === "light" ? CHART_COLORS.ember : CHART_COLORS.mint }}
        />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ Day strip */

export function DayStrip({
  days,
  selected,
  onSelect,
  className,
}: {
  days: number[];
  selected: number;
  onSelect?: (d: number) => void;
  className?: string;
}) {
  return (
    <div className={cn("flex gap-1.5", className)} role="radiogroup" aria-label="Day">
      {days.map((d) => (
        <button
          key={d}
          type="button"
          role="radio"
          aria-checked={d === selected}
          onClick={() => onSelect?.(d)}
          className={cn(
            "grid h-7 min-w-7 place-items-center rounded-lg border px-1 text-[10px] tabular-nums transition-colors",
            d === selected ? "border-white bg-white text-ink" : "border-white/15 bg-white/5 text-white/70 hover:bg-white/10",
          )}
        >
          {d}
        </button>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ Avatars */

const AVATAR_TINTS = [CHART_COLORS.ember, CHART_COLORS.sun, CHART_COLORS.mint, CHART_COLORS.violet, CHART_COLORS.sky];

export function Avatar({ name, size = 34, className }: { name: string; size?: number; className?: string }) {
  const initials = name.split(" ").map((n) => n[0]).join("").slice(0, 2).toUpperCase();
  const tint = AVATAR_TINTS[[...name].reduce((s, c) => s + c.charCodeAt(0), 0) % AVATAR_TINTS.length];
  return (
    <span
      title={name}
      className={cn("inline-grid shrink-0 place-items-center rounded-full border-2 border-paper font-semibold text-white", className)}
      style={{ width: size, height: size, background: tint, fontSize: size * 0.34 }}
    >
      {initials}
    </span>
  );
}

export function AvatarStack({ names, max = 3, size = 34 }: { names: string[]; max?: number; size?: number }) {
  const shown = names.slice(0, max);
  const rest = names.length - shown.length;
  return (
    <span className="flex items-center" aria-label={names.join(", ")}>
      {shown.map((n, i) => (
        <Avatar key={n} name={n} size={size} className={i > 0 ? "-ml-2.5" : undefined} />
      ))}
      {rest > 0 && (
        <span
          className="-ml-2.5 inline-grid place-items-center rounded-full border-2 border-paper bg-navy text-[10px] font-semibold text-white"
          style={{ width: size, height: size }}
        >
          +{rest}
        </span>
      )}
    </span>
  );
}

/* ------------------------------------------------------------------ Pager dots */

export function PagerDots({
  count,
  active,
  onSelect,
  vertical = true,
  className,
}: {
  count: number;
  active: number;
  onSelect?: (i: number) => void;
  vertical?: boolean;
  className?: string;
}) {
  return (
    <div className={cn("flex gap-1.5", vertical ? "flex-col" : "flex-row", className)}>
      {Array.from({ length: count }, (_, i) => (
        <button
          key={i}
          type="button"
          aria-label={`Slide ${i + 1}`}
          aria-current={i === active}
          onClick={() => onSelect?.(i)}
          className={cn("h-2 w-2 rounded-full transition-colors", i === active ? "bg-white" : "bg-white/25 hover:bg-white/50")}
        />
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ Target columns */

export interface TargetDatum {
  label: string;
  /** The target (e.g. amount due). Drawn as the pale capsule. */
  target: number;
  /** Achieved against it (e.g. collected). Drawn as the fill inside the capsule. */
  actual: number;
}

/**
 * Actual-vs-target per period, one capsule each: the capsule's height is the target, the fill
 * inside it is what was achieved, and the cap reads the attainment %. Ink fill, mint once the
 * target is met. Hover/focus dims the other periods and shows an exact readout — no tinted band.
 */
export function TargetColumns({
  data,
  format = (v) => v.toLocaleString("en-IN"),
  height = 200,
  ariaLabel,
  className,
}: {
  data: TargetDatum[];
  format?: (v: number) => string;
  height?: number;
  ariaLabel: string;
  className?: string;
}) {
  const max = Math.max(1, ...data.map((d) => Math.max(d.target, d.actual)));
  const [hover, setHover] = React.useState<number | null>(null);
  return (
    <figure className={cn("m-0 -mx-1 overflow-x-auto px-1 pt-10 [scrollbar-width:none]", className)} aria-label={ariaLabel}>
      <div style={{ minWidth: data.length * 60 }}>
        <div className="relative flex items-end gap-3 sm:gap-5" style={{ height }} role="list">
          {/* three faint guides at 25 / 50 / 75 % of the scale */}
          {[0.25, 0.5, 0.75].map((g) => (
            <span key={g} aria-hidden className="pointer-events-none absolute inset-x-0 border-t border-dashed border-line" style={{ bottom: `${g * 100}%` }} />
          ))}
          {data.map((d, i) => {
            const pct = d.target > 0 ? d.actual / d.target : 0;
            const met = pct >= 1;
            const dim = hover != null && hover !== i;
            return (
              <div
                key={d.label}
                role="listitem"
                tabIndex={0}
                aria-label={`${d.label}: ${format(d.actual)} of ${format(d.target)}, ${Math.round(pct * 100)}%`}
                onMouseEnter={() => setHover(i)}
                onMouseLeave={() => setHover(null)}
                onFocus={() => setHover(i)}
                onBlur={() => setHover(null)}
                className="relative flex h-full min-w-0 flex-1 flex-col items-center justify-end outline-none transition-opacity duration-300"
                style={{ opacity: dim ? 0.38 : 1 }}
              >
                <span className={cn("mb-2 text-[10.5px] font-semibold tabular-nums", met ? "text-success-600" : "text-ink")}>
                  {Math.round(pct * 100)}%
                </span>
                <div
                  className="relative w-full max-w-[56px] overflow-hidden rounded-[16px] p-1"
                  style={{ height: `${(d.target / max) * 90}%`, background: CHART_COLORS.track }}
                >
                  <span
                    className="absolute inset-x-1 bottom-1 origin-bottom animate-grow-y rounded-[12px] transition-colors duration-300"
                    style={{
                      height: `calc(${Math.min(1, pct) * 100}% - 8px)`,
                      background: met ? CHART_COLORS.mint : CHART_COLORS.ink,
                      animationDelay: `${i * 60}ms`,
                    }}
                  />
                </div>
                {hover === i && (
                  // Outer span owns the centring transform; the inner one owns the animation, so the
                  // rise keyframes (which also set `transform`) can never knock it off-centre.
                  <span className="pointer-events-none absolute -top-9 left-1/2 z-10 -translate-x-1/2">
                    <span className="block animate-rise whitespace-nowrap rounded-full bg-navy px-3 py-1.5 text-[10.5px] font-medium text-white shadow-md">
                      {format(d.actual)} <span className="text-white/50">of {format(d.target)}</span>
                    </span>
                  </span>
                )}
              </div>
            );
          })}
        </div>
        <div className="mt-3 flex gap-3 sm:gap-5" aria-hidden>
          {data.map((d, i) => (
            <span key={d.label} className="flex min-w-0 flex-1 justify-center">
              <span
                className={cn(
                  "max-w-full truncate rounded-full px-2.5 py-1 text-[10px] transition-colors",
                  hover === i ? "bg-navy text-white" : "text-muted",
                )}
              >
                {d.label}
              </span>
            </span>
          ))}
        </div>
      </div>
    </figure>
  );
}
