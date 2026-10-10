"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { paiseToINR, type DashMetric, type DashTone } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { CARD_HIGHLIGHT, gradientCss } from "./colors";
import { useCountUp } from "./use-count-up";

export interface PositionCardProps {
  title: string;
  tone: DashTone;
  icon: LucideIcon;
  paise: number;
  loans: number;
  metric: DashMetric;
  /** One-line caption under the figure. */
  caption?: string;
  /** Definition shown in the hover popover. */
  definition: string;
  /** Extra popover lines (e.g. a reconciliation), rendered under the amount. */
  detail?: React.ReactNode;
  onOpen: (metric: DashMetric) => void;
}

/** Vivid gradient card for one Business-position figure: hover = exact ₹ + loans, click = records. */
export function PositionCard({ title, tone, icon: Icon, paise, loans, metric, caption, definition, detail, onOpen }: PositionCardProps) {
  const animated = useCountUp(paise);
  const [open, setOpen] = React.useState(false);
  const tipId = React.useId();
  const loansText = `${loans.toLocaleString("en-IN")} ${loans === 1 ? "loan" : "loans"}`;

  return (
    <div
      className="relative"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      onKeyDown={(e) => e.key === "Escape" && setOpen(false)}
    >
      <button
        type="button"
        aria-label={`${title}: ${paiseToINR(paise)} across ${loansText}. Open the records behind this number.`}
        aria-describedby={open ? tipId : undefined}
        onClick={() => onOpen(metric)}
        style={{ backgroundImage: gradientCss(tone) }}
        className={cn(
          "group relative flex h-full w-full flex-col overflow-hidden rounded-xl p-4 text-left text-white shadow-md",
          "transition duration-200 hover:-translate-y-0.5 hover:shadow-xl hover:ring-2 hover:ring-white/40",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-white/70 focus-visible:ring-offset-2 focus-visible:ring-offset-navy",
        )}
      >
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
        <span aria-hidden className="relative mt-2 font-serif text-2xl font-bold leading-none tabular-nums">
          {paiseToINR(Math.round(animated))}
        </span>
        <span className="relative mt-3 min-h-4 text-xs text-white/80">{caption ?? " "}</span>
      </button>

      {open && (
        <div
          role="tooltip"
          id={tipId}
          className="pointer-events-none absolute inset-x-2 top-full z-30 mt-2 rounded-lg bg-navy p-3 text-xs text-white shadow-xl"
        >
          <p className="m-0 mb-1.5 font-semibold">{title}</p>
          <p className="m-0 mb-1.5 text-white/80">{definition}</p>
          <p className="m-0 tabular-nums">
            {paiseToINR(paise)} · {loansText}
          </p>
          {detail}
          <p className="m-0 mt-1.5 text-[10px] text-white/60">Click to view records</p>
        </div>
      )}
    </div>
  );
}
