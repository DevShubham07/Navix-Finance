"use client";

/**
 * Shared vertical Journey stepper (staff-only).
 *
 * Renders the 6 macro-stages of a {@link DerivedJourney} as an accessible ordered
 * list — one dot + label + status pill + latest timestamp/actor per stage, each a
 * `<button>` that opens the per-step detail popup. Extracted from
 * {@link ApplicationJourney} (Phase F) so the drawer AND the unified detail page
 * render an identical stepper.
 *
 * Presentational only — no data fetching. The caller derives the journey (via
 * {@link deriveJourney}), computes the active index and handles node clicks. Kept
 * with its `NODE_META` visual map + `latestEvent` helper so the treatment travels
 * with the component. Staff-only (renders actor names); never import under the
 * borrower route tree.
 */

import * as React from "react";
import {
  Check,
  Circle,
  CircleDot,
  Clock,
  AlertTriangle,
  XCircle,
  Ban,
  MinusCircle,
  ArrowRight,
  type LucideIcon,
} from "lucide-react";
import type { EventView } from "@/lib/api/applications";
import type { JourneyStage, JourneyStageState } from "@/lib/domain/journey";
import { cn, formatDateTime } from "@/lib/utils";

interface NodeMeta {
  Icon: LucideIcon;
  statusText: string;
  /** Circle/dot classes. */
  dot: string;
  /** Small text pill classes for the status label (never colour-only). */
  pill: string;
}

/** Visual treatment per stage state — icon + text label + tone (never colour-only). */
const NODE_META: Record<JourneyStageState, NodeMeta> = {
  upcoming: {
    Icon: Circle,
    statusText: "Upcoming",
    dot: "border-2 border-line bg-white text-muted",
    pill: "bg-grey-100 text-muted",
  },
  pending: {
    Icon: Clock,
    statusText: "Pending",
    dot: "bg-warning-500 text-white",
    pill: "bg-warning-100 text-warning-800",
  },
  in_progress: {
    Icon: CircleDot,
    statusText: "In progress",
    dot: "bg-navy text-white",
    pill: "bg-info-100 text-info-700",
  },
  done: {
    Icon: Check,
    statusText: "Completed",
    dot: "bg-success-600 text-white",
    pill: "bg-success-100 text-success-700",
  },
  skipped: {
    Icon: MinusCircle,
    statusText: "Skipped — pre-approved",
    dot: "border-2 border-dashed border-line bg-white text-muted",
    pill: "bg-grey-100 text-muted",
  },
  active: {
    Icon: CircleDot,
    statusText: "Active",
    dot: "bg-navy text-white ring-4 ring-navy-tint",
    pill: "bg-navy-tint text-navy",
  },
  overdue: {
    Icon: AlertTriangle,
    statusText: "Overdue",
    dot: "bg-warning-500 text-white ring-4 ring-warning-100",
    pill: "bg-warning-100 text-warning-800",
  },
  defaulted: {
    Icon: AlertTriangle,
    statusText: "Defaulted",
    dot: "bg-error-600 text-white",
    pill: "bg-error-100 text-error-700",
  },
  failed: {
    Icon: XCircle,
    statusText: "Disbursement failed",
    dot: "bg-error-500 text-white",
    pill: "bg-error-100 text-error-700",
  },
  rejected: {
    Icon: XCircle,
    statusText: "Rejected",
    dot: "bg-error-600 text-white",
    pill: "bg-error-100 text-error-700",
  },
  cancelled: {
    Icon: Ban,
    statusText: "Cancelled",
    dot: "bg-muted text-white",
    pill: "bg-grey-100 text-muted",
  },
  written_off: {
    Icon: Ban,
    statusText: "Written off",
    dot: "bg-error-700 text-white",
    pill: "bg-error-100 text-error-700",
  },
};

/** The newest event in a stage bucket (drives the row's timestamp + actor). */
function latestEvent(events: EventView[]): EventView | null {
  let latest: EventView | null = null;
  for (const e of events) {
    if (latest == null || new Date(e.at).getTime() >= new Date(latest.at).getTime()) {
      latest = e;
    }
  }
  return latest;
}

export interface JourneyStepperProps {
  /** The derived journey's ordered stages. */
  stages: JourneyStage[];
  /** Index of the current stage (the last non-`upcoming` stage) — gets `aria-current="step"`. */
  activeIndex: number;
  /** Open the per-step detail popup for a clicked stage node. */
  onStageClick: (stage: JourneyStage) => void;
}

/** The vertical stage stepper — one `<button>` node per macro-stage. */
export function JourneyStepper({ stages, activeIndex, onStageClick }: JourneyStepperProps) {
  return (
    <ol className="relative">
      {stages.map((stage, i) => {
        const meta = NODE_META[stage.state];
        const Icon = meta.Icon;
        const isActive = i === activeIndex;
        const isLast = i === stages.length - 1;
        const connectorGreen = stage.state === "done";
        const latest = latestEvent(stage.events);
        return (
          <li key={stage.key} className="relative">
            {!isLast && (
              <span
                aria-hidden
                className={cn(
                  "absolute left-[19px] top-9 h-[calc(100%-1.75rem)] w-0.5",
                  connectorGreen ? "bg-success-600" : "bg-line",
                )}
              />
            )}
            <button
              type="button"
              onClick={() => onStageClick(stage)}
              aria-current={isActive ? "step" : undefined}
              aria-label={`${stage.label} — ${meta.statusText}. View step details`}
              className="group flex w-full items-start gap-4 rounded-lg px-1 py-1.5 text-left transition hover:bg-grey-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-navy focus-visible:ring-offset-1"
            >
              <span
                className={cn(
                  "z-10 mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full",
                  meta.dot,
                  isActive && "shadow-md",
                )}
              >
                <Icon size={16} strokeWidth={2.5} />
              </span>
              <span className="min-w-0 flex-1 pb-5">
                <span className="flex flex-wrap items-center gap-2">
                  <span
                    className={cn(
                      "font-semibold",
                      isActive ? "text-navy" : "text-ink",
                      stage.state === "upcoming" && "text-muted",
                    )}
                  >
                    {stage.label}
                  </span>
                  <span
                    className={cn(
                      "rounded-full px-2 py-0.5 text-[11px] font-semibold",
                      meta.pill,
                    )}
                  >
                    {meta.statusText}
                  </span>
                </span>
                <span className="mt-0.5 block text-xs text-muted">
                  {latest ? formatDateTime(latest.at) : "Not yet reached"}
                  {latest?.actorName ? ` · ${latest.actorName}` : ""}
                </span>
              </span>
              <ArrowRight
                size={14}
                className="mt-2 flex-shrink-0 text-muted opacity-0 transition group-hover:opacity-100"
                aria-hidden
              />
            </button>
          </li>
        );
      })}
    </ol>
  );
}
