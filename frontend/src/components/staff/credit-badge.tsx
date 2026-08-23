import * as React from "react";
import { cn } from "@/lib/utils";
import { StarRating } from "@/components/ui/star-rating";
import { bureauLabel } from "@/lib/credit/bureau-label";

/** Tone the pill by rating: recommend → green, refer → amber, decline → red. */
function tone(rating: number | null | undefined): string {
  if (rating == null) return "bg-neutral-100 text-neutral-700";
  if (rating >= 3.5) return "bg-success-100 text-success-800";
  if (rating >= 2.5) return "bg-warning-100 text-warning-800";
  return "bg-error-100 text-error-800";
}

/**
 * Staff-only credit pill: the bureau score + 1–5★ rating, e.g. "CRIF 778 · ★★★★☆ 4.0".
 * The score is shown at full contrast so it can't be missed.
 * Renders nothing when there's no rating or score (so it can be dropped into any row safely).
 *
 * The score is labelled with the bureau it actually came from — see {@link bureauLabel}. It used to
 * be hardcoded "CIBIL", which was never true: scores came from Experian and now come from CRIF
 * Highmark. Callers that do not know the source get the neutral "Bureau", which is vague but honest.
 *
 * `compact` drops the bureau word and the five star glyphs for a "778 · 4.0★" pill — the stars
 * cost ~70px, which is the difference between a queue row fitting its panel and the whole grid
 * scrolling sideways. The full reading still shows on hover via `title`.
 */
export function CreditBadge({
  starRating,
  creditScore,
  recommendation,
  bureauSource,
  compact,
  className,
}: {
  starRating?: number | null;
  creditScore?: number | null;
  recommendation?: string | null;
  /** Which bureau produced the score (`FINTRIX_CRIF`, `DIGITAP_EXPERIAN`, …); labels the pill. */
  bureauSource?: string | null;
  compact?: boolean;
  className?: string;
}) {
  if (starRating == null && creditScore == null) return null;
  const bureau = bureauLabel(bureauSource);
  const title = [
    creditScore != null ? `${bureau} ${creditScore}` : null,
    starRating != null ? `${starRating.toFixed(1)}★` : null,
    recommendation,
  ]
    .filter(Boolean)
    .join(" · ");
  if (compact) {
    return (
      <span
        className={cn(
          "inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums",
          tone(starRating),
          className,
        )}
        title={title || undefined}
      >
        {creditScore != null && <span className="font-bold">{creditScore}</span>}
        {starRating != null && (
          <>
            {creditScore != null && <span aria-hidden className="opacity-50">·</span>}
            <span>{starRating.toFixed(1)}★</span>
          </>
        )}
      </span>
    );
  }
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
          {bureau} <span className="font-bold">{creditScore}</span>
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
