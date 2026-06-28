import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Pure-CSS star rating. Renders an empty star track with a gold overlay clipped to the
 * value/outOf percentage, so fractional ratings (e.g. 4.0, 4.5) render as partial fills.
 */
export function StarRating({
  value,
  outOf = 5,
  size = "1em",
  showValue = false,
  className,
}: {
  value: number | null | undefined;
  outOf?: number;
  /** Any CSS length — controls the star glyph size (e.g. "1rem", "14px"). */
  size?: string;
  /** Append the numeric rating (e.g. "4.0") after the stars. */
  showValue?: boolean;
  className?: string;
}) {
  const v = Math.max(0, Math.min(outOf, value ?? 0));
  const pct = outOf > 0 ? (v / outOf) * 100 : 0;
  const stars = "★".repeat(outOf);
  return (
    <span className={cn("inline-flex items-center gap-1.5", className)}>
      <span
        className="relative inline-block select-none leading-none tracking-[0.08em]"
        style={{ fontSize: size }}
        role="img"
        aria-label={`${v.toFixed(1)} out of ${outOf} stars`}
      >
        <span className="text-neutral-300">{stars}</span>
        <span
          className="absolute inset-0 overflow-hidden whitespace-nowrap text-gold"
          style={{ width: `${pct}%` }}
          aria-hidden
        >
          {stars}
        </span>
      </span>
      {showValue && <span className="text-xs font-semibold tabular-nums text-ink">{v.toFixed(1)}</span>}
    </span>
  );
}
