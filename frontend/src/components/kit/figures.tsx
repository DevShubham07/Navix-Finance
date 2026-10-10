import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Figures — how numbers look across the product.
 *
 *   <Figure>      headline number in the condensed display face ("₹ 2,56,342")
 *   <DeltaChip>   "+6%" mint / red / neutral pill; direction is a glyph AND colour
 *   <StatBlock>   label + Figure + optional chip + caption (the "Overall Sales" head)
 *   <MetricTile>  small square KPI: figure + chip top, label + icon pill bottom ("8.6% Conversion")
 */

const SIZES = {
  sm: "text-[1.6rem]",
  md: "text-[2.2rem]",
  lg: "text-[2.9rem]",
  xl: "text-[4.6rem]",
} as const;

export function Figure({
  children,
  size = "md",
  className,
}: {
  children: React.ReactNode;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  return <span className={cn("figure-display block text-inherit", SIZES[size], className)}>{children}</span>;
}

export function DeltaChip({
  pct,
  goodWhenUp = true,
  className,
}: {
  /** Percent change, e.g. 6 for +6%. null/undefined renders nothing (never a fake 0). */
  pct: number | null | undefined;
  goodWhenUp?: boolean;
  className?: string;
}) {
  if (pct == null || !Number.isFinite(pct)) return null;
  const flat = Math.abs(pct) < 0.05;
  const up = pct > 0;
  const good = up === goodWhenUp;
  const tone = flat ? "delta-chip--flat" : good ? "delta-chip--up" : "delta-chip--down";
  return (
    <span className={cn("delta-chip", tone, className)} title={`${up ? "Up" : "Down"} ${Math.abs(pct).toFixed(1)}% vs previous period`}>
      {flat ? "±" : up ? "+" : "−"}
      {Math.abs(pct) % 1 === 0 ? Math.abs(pct) : Math.abs(pct).toFixed(1)}%
    </span>
  );
}

export function StatBlock({
  label,
  value,
  delta,
  caption,
  size = "lg",
  className,
}: {
  label: React.ReactNode;
  value: React.ReactNode;
  delta?: number | null;
  caption?: React.ReactNode;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  return (
    <div className={cn("min-w-0", className)}>
      <p className="m-0 text-xs text-muted">{label}</p>
      <div className="mt-1.5 flex items-start gap-2">
        <Figure size={size}>{value}</Figure>
        <DeltaChip pct={delta} className="mt-1" />
      </div>
      {caption && <p className="m-0 mt-2 text-xs text-muted">{caption}</p>}
    </div>
  );
}

export function MetricTile({
  value,
  label,
  delta,
  icon,
  className,
}: {
  value: React.ReactNode;
  label: React.ReactNode;
  delta?: number | null;
  icon?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("surface flex min-h-[9.5rem] flex-col justify-between p-5", className)}>
      <div className="flex items-start justify-between gap-2">
        <Figure size="md">{value}</Figure>
        <DeltaChip pct={delta} />
      </div>
      <div className="flex items-end justify-between gap-2">
        <p className="m-0 max-w-[8rem] text-xs leading-snug text-muted">{label}</p>
        {icon && <span className="icon-pill" aria-hidden>{icon}</span>}
      </div>
    </div>
  );
}
