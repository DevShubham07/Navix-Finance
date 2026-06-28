import * as React from "react";
import { cn } from "@/lib/utils";
import { StarRating } from "@/components/ui/star-rating";

/** Tone the pill by rating: recommend → green, refer → amber, decline → red. */
function tone(rating: number | null | undefined): string {
  if (rating == null) return "bg-neutral-100 text-neutral-700";
  if (rating >= 3.5) return "bg-success-100 text-success-800";
  if (rating >= 2.5) return "bg-warning-100 text-warning-800";
  return "bg-error-100 text-error-800";
}

/**
 * Compact staff-only credit pill: 1–5★ rating + numeric + bureau score, e.g. "★★★★☆ 4.0 · 778".
 * Renders nothing when there's no rating or score (so it can be dropped into any row safely).
 */
export function CreditBadge({
  starRating,
  creditScore,
  recommendation,
  className,
}: {
  starRating?: number | null;
  creditScore?: number | null;
  recommendation?: string | null;
  className?: string;
}) {
  if (starRating == null && creditScore == null) return null;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold",
        tone(starRating),
        className,
      )}
      title={recommendation ?? undefined}
    >
      {starRating != null && (
        <>
          <StarRating value={starRating} size="0.8em" />
          <span className="tabular-nums">{starRating.toFixed(1)}</span>
        </>
      )}
      {creditScore != null && (
        <span className="tabular-nums opacity-80">
          {starRating != null ? "· " : "Score "}
          {creditScore}
        </span>
      )}
    </span>
  );
}
