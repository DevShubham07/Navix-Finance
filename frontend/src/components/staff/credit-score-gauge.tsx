"use client";

/**
 * Animated speedometer-style credit-score gauge (staff-only).
 *
 * A semicircular dial split into the five CIBIL bands, with curved band labels, a needle that
 * sweeps to the score and a counting-up figure. The score axis (300–900) is mapped *linearly*
 * across the 180°, and each band's wedge is drawn at its true proportional width — so the needle
 * angle is an honest reading of the score, not a decorative one.
 *
 * Pure inline SVG + CSS transitions: the repo has no animation library and the rest of the design
 * system animates with plain keyframes/transitions (see the marketing donut in marketing-theme.css).
 *
 * NEVER render this on a borrower route — the bureau score and ★ rating are staff-only.
 */

import * as React from "react";
import { StarRating } from "@/components/ui/star-rating";
import { cn } from "@/lib/utils";
import { bureauStateLabel } from "@/components/staff/bureau-state";
import type { BureauState } from "@/lib/api/applications";

const MIN_SCORE = 300;
const MAX_SCORE = 900;

/** Band table — the single source of truth for the wedges, labels and verdict tone. */
const BANDS = [
  { from: 300, to: 550, label: "POOR", color: "#D33C32", tone: "bg-error-100 text-error-800" },
  { from: 550, to: 650, label: "FAIR", color: "#E07B39", tone: "bg-warning-100 text-warning-800" },
  { from: 650, to: 700, label: "GOOD", color: "#D7B02A", tone: "bg-warning-100 text-warning-800" },
  { from: 700, to: 750, label: "VERY GOOD", color: "#7FBF44", tone: "bg-success-100 text-success-800" },
  { from: 750, to: 900, label: "EXCELLENT", color: "#22A06B", tone: "bg-success-100 text-success-800" },
] as const;

/**
 * Curved labels are drawn per *zone*, not per band: GOOD and VERY GOOD only span 50 score points
 * each (15° of arc), which is far too narrow to seat their text without the neighbouring labels
 * running into each other. Merging them into one "GOOD" zone matches the reference art, which
 * likewise carries four labels over more wedges.
 */
const LABEL_ZONES = [
  { from: 300, to: 550, label: "POOR" },
  { from: 550, to: 650, label: "FAIR" },
  { from: 650, to: 750, label: "GOOD" },
  { from: 750, to: 900, label: "EXCELLENT" },
] as const;

const EMPTY_COLOR = "#C6C3BA"; // neutral-300 — used when there is no bureau score yet.

/** The band a score falls in; upper boundaries read as the higher band (750 → EXCELLENT). */
export function bandForScore(score: number | null | undefined) {
  if (score == null || !Number.isFinite(score)) return null;
  const s = clamp(score);
  for (let i = BANDS.length - 1; i >= 0; i -= 1) {
    if (s >= BANDS[i].from) return BANDS[i];
  }
  return BANDS[0];
}

function clamp(score: number): number {
  return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
}

/** Score → degrees clockwise from the left-hand stop (0° = 300, 180° = 900). */
function angleFor(score: number): number {
  return ((clamp(score) - MIN_SCORE) / (MAX_SCORE - MIN_SCORE)) * 180;
}

const CX = 100;
const CY = 100;
const R = 74; // centreline radius of the band arc
const LABEL_R = 92; // guide-arc radius for the curved band labels

/** Polar → cartesian, with 0° at the left-hand stop sweeping clockwise over the top. */
function polar(r: number, deg: number): { x: number; y: number } {
  const rad = ((180 - deg) * Math.PI) / 180;
  return { x: CX + r * Math.cos(rad), y: CY - r * Math.sin(rad) };
}

/** An arc path between two angles (always < 180°, so large-arc is 0; sweep 1 = clockwise). */
function arcPath(r: number, fromDeg: number, toDeg: number): string {
  const a = polar(r, fromDeg);
  const b = polar(r, toDeg);
  return `M ${a.x.toFixed(2)} ${a.y.toFixed(2)} A ${r} ${r} 0 0 1 ${b.x.toFixed(2)} ${b.y.toFixed(2)}`;
}

/** Arc length, used to seed stroke-dasharray for the draw-in. */
function arcLength(r: number, fromDeg: number, toDeg: number): number {
  return ((toDeg - fromDeg) * Math.PI * r) / 180;
}

function prefersReducedMotion(): boolean {
  if (typeof window === "undefined") return true;
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
}

const SIZES = {
  sm: { width: 200, bandWidth: 15, label: 8.4, score: 26, caption: 8, star: "0.72rem" },
  md: { width: 300, bandWidth: 17, label: 9, score: 34, caption: 9.5, star: "0.88rem" },
} as const;

export function CreditScoreGauge({
  score,
  starRating,
  recommendation,
  state,
  size = "md",
  className,
}: {
  /** Bureau score (300–900). `null` renders the empty/greyed dial. */
  score: number | null | undefined;
  /** 1–5 recommendation rating, shown as stars under the dial. */
  starRating?: number | null;
  /** Underwriter verdict text, shown as a pill under the dial. */
  recommendation?: string | null;
  /** Distinguishes the empty-dial caption between "never fetched" and "no record found". Omit to
   *  keep the pre-existing "NO BUREAU PULL YET" caption unchanged. */
  state?: BureauState;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  const uid = React.useId().replace(/[:]/g, "");
  const dims = SIZES[size];
  const has = score != null && Number.isFinite(score);
  const target = has ? clamp(score as number) : MIN_SCORE;
  const band = bandForScore(score);

  // `armed` gates the whole animation: we paint the resting state first, then flip it on the next
  // frame so the CSS transitions actually run (a same-frame change would just snap).
  const [armed, setArmed] = React.useState(false);
  // Seeded at the floor, not at `target`: seeding with the real score makes the final figure flash
  // for a frame before the effect resets it to count up. Server and client agree on this value, so
  // it is also hydration-safe.
  const [display, setDisplay] = React.useState(MIN_SCORE);
  // Resolved in an effect, never during render — reading matchMedia while rendering would make the
  // server and client markup disagree.
  const [reduced, setReduced] = React.useState(false);

  React.useEffect(() => {
    const isReduced = prefersReducedMotion();
    setReduced(isReduced);
    if (isReduced) {
      setArmed(true);
      setDisplay(target);
      return;
    }
    setArmed(false);
    setDisplay(MIN_SCORE);

    let countFrame = 0;
    const raf = requestAnimationFrame(() => {
      setArmed(true);
      if (!has) return;
      const start = performance.now();
      const DURATION = 1100;
      const tick = (now: number) => {
        // Clamped at BOTH ends: rAF hands back the frame timestamp, which can predate the `start`
        // we captured a moment earlier — an unclamped negative `t` undershoots below MIN_SCORE.
        const t = Math.min(1, Math.max(0, (now - start) / DURATION));
        const eased = 1 - Math.pow(1 - t, 3); // easeOutCubic
        setDisplay(Math.round(MIN_SCORE + (target - MIN_SCORE) * eased));
        if (t < 1) countFrame = requestAnimationFrame(tick);
      };
      countFrame = requestAnimationFrame(tick);
    });

    return () => {
      cancelAnimationFrame(raf);
      cancelAnimationFrame(countFrame);
    };
  }, [target, has]);

  const needleAngle = armed ? angleFor(target) : 0;
  const emptyCaption = state === "NO_RECORD" ? bureauStateLabel("NO_RECORD", "caption") : "NO BUREAU PULL YET";
  const ariaLabel = has
    ? `Credit score ${target} out of ${MAX_SCORE} — ${band?.label ?? ""}`
    : state === "NO_RECORD"
      ? "Credit score not available — no bureau record found"
      : "Credit score not available — no bureau pull yet";

  return (
    <div className={cn("flex flex-col items-center", className)}>
      <svg
        viewBox="0 0 200 154"
        width={dims.width}
        className="max-w-full"
        role="img"
        aria-label={ariaLabel}
      >
        <defs>
          {LABEL_ZONES.map((z, i) => (
            <path
              key={z.label}
              id={`${uid}-lbl-${i}`}
              fill="none"
              d={arcPath(LABEL_R, angleFor(z.from), angleFor(z.to))}
            />
          ))}
        </defs>

        {/* Coloured band wedges — each draws itself in, staggered left → right. */}
        <g aria-hidden>
          {BANDS.map((b, i) => {
            const from = angleFor(b.from);
            const to = angleFor(b.to);
            const len = arcLength(R, from, to);
            return (
              <path
                key={b.label}
                d={arcPath(R, from, to)}
                fill="none"
                stroke={has ? b.color : EMPTY_COLOR}
                strokeWidth={dims.bandWidth}
                strokeLinecap="butt"
                style={{
                  strokeDasharray: len,
                  strokeDashoffset: armed ? 0 : len,
                  transition: reduced
                    ? undefined
                    : `stroke-dashoffset .55s ease-out ${i * 0.09}s, stroke .3s ease`,
                }}
              />
            );
          })}
        </g>

        {/* Curved zone labels, riding a guide arc just outside the wedges. */}
        <g aria-hidden>
          {LABEL_ZONES.map((z, i) => (
            <text
              key={z.label}
              fontSize={dims.label}
              fontWeight={700}
              letterSpacing="0.06em"
              fill={has ? "#0C2238" : "#8593A6"}
              style={{
                opacity: armed ? 1 : 0,
                transition: reduced ? undefined : `opacity .4s ease ${0.25 + i * 0.09}s`,
              }}
            >
              <textPath href={`#${uid}-lbl-${i}`} startOffset="50%" textAnchor="middle">
                {z.label}
              </textPath>
            </text>
          ))}
        </g>

        {/* Needle + hub. Rotates about the dial centre; the bezier overshoots slightly so it
            settles like a real speedometer. */}
        <g
          style={{
            transform: `rotate(${needleAngle}deg)`,
            transformOrigin: `${CX}px ${CY}px`,
            transition: reduced ? undefined : "transform 1.25s cubic-bezier(.22,1.4,.36,1) .1s",
          }}
          aria-hidden
        >
          <path
            d={`M ${CX - 3.4} ${CY} L ${CX - 1.6} ${CY - R - 4} L ${CX + 1.6} ${CY - R - 4} L ${CX + 3.4} ${CY} Z`}
            transform={`rotate(-90 ${CX} ${CY})`}
            fill="#0C2238"
          />
        </g>
        <circle cx={CX} cy={CY} r={12} fill="#FFFFFF" stroke="#EAE1D0" strokeWidth={1} aria-hidden />
        <circle cx={CX} cy={CY} r={5} fill={has ? (band?.color ?? "#0C2238") : EMPTY_COLOR} aria-hidden />

        {/* Axis end-stops, tucked under the arc ends. */}
        <text x={CX - R} y={CY + 13} textAnchor="middle" fontSize={dims.caption - 1.5} fill="#8593A6" aria-hidden>
          {MIN_SCORE}
        </text>
        <text x={CX + R} y={CY + 13} textAnchor="middle" fontSize={dims.caption - 1.5} fill="#8593A6" aria-hidden>
          {MAX_SCORE}
        </text>

        {/* Score figure + caption sit BELOW the dial — inside the arch they collide with the needle. */}
        <text
          x={CX}
          y={CY + 32}
          textAnchor="middle"
          fontSize={has ? dims.score : dims.score * 0.55}
          fontWeight={700}
          fill={has ? "#0C2540" : "#8593A6"}
          style={{ fontVariantNumeric: "tabular-nums" }}
          aria-hidden
        >
          {has ? display : "—"}
        </text>
        <text
          x={CX}
          y={CY + 47}
          textAnchor="middle"
          fontSize={dims.caption}
          fontWeight={700}
          letterSpacing="0.14em"
          fill="#8593A6"
          aria-hidden
        >
          {has ? "CREDIT SCORE" : emptyCaption}
        </text>
      </svg>

      {(band || starRating != null || recommendation) && (
        <div className="mt-1 flex flex-wrap items-center justify-center gap-x-3 gap-y-1.5">
          {band && (
            <span className={cn("rounded-full px-2.5 py-0.5 text-xs font-semibold", band.tone)}>
              {recommendation || band.label}
            </span>
          )}
          {starRating != null && (
            <span className="flex items-center gap-1.5">
              <StarRating value={starRating} size={dims.star} />
              <span className="text-xs font-semibold tabular-nums text-ink">{starRating.toFixed(1)} / 5</span>
            </span>
          )}
        </div>
      )}
    </div>
  );
}
