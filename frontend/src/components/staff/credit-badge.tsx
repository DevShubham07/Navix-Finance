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
 * Staff-only credit pill: the CIBIL/bureau score + 1–5★ rating, e.g. "CIBIL 778 · ★★★★☆ 4.0".
 * The score is always labelled "CIBIL" and shown at full contrast so it can't be missed.
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
  const title = [creditScore != null ? `CIBIL ${creditScore}` : null, recommendation]
    .filter(Boolean)
    .join(" · ");
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold",
        tone(starRating),
        className,
      )}
      title={title || undefined}
    >
      {creditScore != null && (
        <span className="tabular-nums">
          CIBIL <span className="font-bold">{creditScore}</span>
        </span>
      )}
      {starRating != null && (
        <>
          {creditScore != null && <span aria-hidden className="opacity-50">·</span>}
          <StarRating value={starRating} size="0.85em" />
          <span className="tabular-nums">{starRating.toFixed(1)}</span>
        </>
      )}
    </span>
  );
}
