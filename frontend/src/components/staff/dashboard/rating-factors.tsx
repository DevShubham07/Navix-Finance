"use client";

import * as React from "react";
import { StarRating } from "@/components/ui/star-rating";
import type { DashFactor } from "@/lib/api/applications";
import { PCT_TONE_COLOR, PCT_TONE_FILL, pctTone } from "./colors";

/** Score 0..10 -> "7.40"; null stays an em dash (cannot be measured), never 0. */
export function fmtScore(score: number | null | undefined): string {
  return score == null ? "—" : score.toFixed(2);
}

/**
 * One white tile per rating factor: label, month-to-date value, stars and a tone-coloured capsule
 * bar, with "Overall x / 10" as an ink pill in the corner. Tooltip text carries weight and exact value.
 */
export function RatingFactors({
  factors,
  score,
  title = "Rating factors",
  periodLabel,
}: {
  factors: DashFactor[] | null | undefined;
  score: number | null | undefined;
  title?: string;
  periodLabel?: string;
}) {
  if (!factors || factors.length === 0) return null;
  return (
    <section className="surface p-5">
      <header className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="m-0 font-sans text-base font-medium tracking-tight text-ink">{title}</h3>
          {periodLabel && <p className="m-0 mt-1 text-xs text-muted">{periodLabel}</p>}
        </div>
        <span className="rounded-full bg-navy px-3 py-1 text-xs font-semibold text-white shadow-pill">
          Overall {fmtScore(score)} / 10
        </span>
      </header>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {factors.map((f) => {
          const tone = pctTone(f.value);
          const width = f.value == null ? 0 : Math.max(0, Math.min(1, f.value)) * 100;
          return (
            <div
              key={f.key}
              className="surface-tile p-3"
              title={`${f.label}: ${f.display} (weight ${f.weight})`}
            >
              <div className="flex items-start justify-between gap-2">
                <span className="text-xs font-medium text-ink">{f.label}</span>
                <span className="text-sm font-semibold tabular-nums" style={{ color: PCT_TONE_COLOR[tone] }}>
                  {f.display}
                </span>
              </div>
              <div className="mt-1.5 flex items-center justify-between">
                <StarRating value={f.stars} size="0.95rem" />
                <span className="text-[10px] text-muted">weight {f.weight}</span>
              </div>
              <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-chart-track" aria-hidden>
                <div className="h-full rounded-full" style={{ width: `${width}%`, background: PCT_TONE_FILL[tone] }} />
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
