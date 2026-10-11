"use client";

import * as React from "react";
import { STAGE_LABELS, STAGE_ORDER, stageOf, type JourneyStageState } from "@/lib/domain/journey";
import type { ApplicationStatus } from "@/lib/api/applications";
import { stepInfo } from "@/lib/domain/journey-steps";
import { cn } from "@/lib/utils";
import { NODE_META } from "./journey-stepper";

export interface StageSignalProps {
  status?: string | null;
  loanStatus?: string | null;
  journeyStep?: string | null;
  dpd?: number | null;
  compact?: boolean;
}

const OVERDUE_LOAN = new Set(["OVERDUE", "IN_COLLECTIONS"]);
const OVERDUE_APP = new Set(["OVERDUE", "DEFAULTED"]);

/** Bar colour: the stepper's dot tone minus its ring/border chrome. */
function bar(state: JourneyStageState | "overdue"): string {
  const s = state === "pending" || state === "in_progress" ? "pending" : state === "overdue" ? "defaulted" : state;
  return NODE_META[s].dot
    .split(" ")
    .filter((c) => !c.startsWith("ring") && !c.startsWith("border") && !c.startsWith("text-"))
    .join(" ");
}

/** Compact 6-segment stage meter + caption (Started → KYC → Credit → Disbursal → Active → Closed). */
export function StageSignal({ status, loanStatus, journeyStep, dpd, compact }: StageSignalProps) {
  if (!status) return <span className="text-xs text-muted">—</span>;
  const so = stageOf(status as ApplicationStatus) as ReturnType<typeof stageOf> | undefined;
  if (!so) return <span className="text-xs text-muted">{status}</span>;
  const overdue = OVERDUE_LOAN.has(loanStatus ?? "") || OVERDUE_APP.has(status);
  const cur = STAGE_ORDER.indexOf(so.stage);
  const curState: JourneyStageState | "overdue" = overdue ? "overdue" : so.state;
  const info = stepInfo(journeyStep);

  let caption: string = STAGE_LABELS[so.stage];
  if (overdue) caption = dpd != null && dpd > 0 ? `Overdue · ${dpd} DPD` : "Overdue";
  else if (status === "DRAFT" && info?.journey === "intake") caption = `Signup · ${info.label} (${info.n}/${info.total})`;
  else if (status === "SANCTIONED" && info?.journey === "offer") caption = `Offer · ${info.label} (${info.n}/${info.total})`;
  else if (so.state === "rejected" || so.state === "cancelled") caption = `${NODE_META[so.state].statusText} · ${caption}`;

  const detail = `${caption} — status ${status}${loanStatus ? `, loan ${loanStatus}` : ""}`;

  return (
    <div className={cn("flex flex-col gap-1", compact ? "w-[120px]" : "w-[150px]")} title={detail} role="img" aria-label={detail}>
      <div className="flex gap-0.5">
        {STAGE_ORDER.map((k, i) => (
          <span
            key={k}
            className={cn(
              "h-1.5 flex-1 rounded-full",
              i < cur ? bar("done") : i === cur ? bar(curState) : "bg-grey-100",
            )}
          />
        ))}
      </div>
      <span className="truncate text-xs text-muted">{caption}</span>
    </div>
  );
}
