"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { paiseToINR, type DashMetric, type DashTone } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { CARD_BUTTON, CardIcon, CardPopover } from "./kpi-card";
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

/**
 * Light-panel card for one Business-position figure (the black feature card for a `navy` tone):
 * muted title, the rupees in the display face, the tone only on the icon pill. Hover = exact ₹ +
 * loans, click = records.
 */
export function PositionCard({ title, tone, icon, paise, loans, metric, caption, definition, detail, onOpen }: PositionCardProps) {
  const animated = useCountUp(paise);
  const [open, setOpen] = React.useState(false);
  const tipId = React.useId();
  const loansText = `${loans.toLocaleString("en-IN")} ${loans === 1 ? "loan" : "loans"}`;
  const dark = tone === "navy";

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
        className={cn(dark ? "surface-dark" : "surface", CARD_BUTTON, "p-4")}
      >
        <span className="flex items-start justify-between gap-2">
          <span className={cn("pt-1 text-xs font-medium", dark ? "text-white/65" : "text-slate")}>{title}</span>
          <CardIcon icon={icon} tone={tone} dark={dark} />
        </span>
        <span aria-hidden className={cn("figure-display mt-3 text-[1.6rem]", dark ? "text-white" : "text-ink")}>
          {paiseToINR(Math.round(animated))}
        </span>
        <span className={cn("mt-2 min-h-4 text-[11px]", dark ? "text-white/65" : "text-slate")}>{caption ?? " "}</span>
      </button>

      {open && (
        <CardPopover id={tipId} title={title}>
          <p className="m-0 mb-1.5 text-slate">{definition}</p>
          <p className="m-0 font-medium tabular-nums">
            {paiseToINR(paise)} · {loansText}
          </p>
          {detail}
        </CardPopover>
      )}
    </div>
  );
}
