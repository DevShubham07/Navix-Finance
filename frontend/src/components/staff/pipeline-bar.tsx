"use client";

/**
 * Pipeline-at-a-glance bar (staff dashboard, Layer 3).
 *
 * One segment per macro-stage ({@link STAGE_ORDER}) showing how many
 * applications currently sit in it, summed from the per-status `staffApi.stats()`
 * counts by bucketing each status through {@link stageOf}. It replaces the old
 * seven-stat-card grid with a single, role-aware overview.
 *
 * Role emphasis: ADMIN sees every segment at full opacity; a role that acts on a
 * specific span (KYC, credit, disbursement, collections) gets that span
 * highlighted and the rest dimmed. Horizontally scrollable on narrow screens.
 *
 * The two polymorphic terminal statuses (REJECTED / CANCELLED) carry no stage of
 * their own without per-application event history, so they are excluded from the
 * bar entirely — they are terminal outcomes, not live pipeline load.
 */

import * as React from "react";
import { InfoTooltip } from "@/components/ui";
import {
  STAGE_ORDER,
  STAGE_LABELS,
  stageOf,
  type JourneyStageKey,
} from "@/lib/domain/journey";
import type { StaffRole } from "@/lib/auth/rbac";
import type { ApplicationStatus } from "@/lib/api/applications";
import { cn } from "@/lib/utils";

/** Per-stage ⓘ copy — reuses the retired stat-card tooltip wording where it maps. */
const STAGE_INFO: Record<JourneyStageKey, string> = {
  STARTED:
    "New applications the borrower is still completing before submitting them for KYC.",
  KYC:
    "Identity (PAN/Aadhaar) and documents awaiting a KYC Approver, plus returning borrowers flagged for a past overdue.",
  CREDIT_REVIEW:
    "Applications being assessed by the Credit Executive (recommend) and Credit Head (final approve, SoD-checked).",
  DISBURSEMENT:
    "Approved loans waiting to be released by the Disbursement Head, then confirmed by the Accountant.",
  ACTIVE_REPAYMENT:
    "Live loans being repaid, including any now past due and worked by Collections.",
  CLOSED: "Fully repaid or written-off loans (terminal).",
};

/** Stages shown subdued — terminal, not active pipeline load. */
const TERMINAL_STAGES: ReadonlySet<JourneyStageKey> = new Set(["CLOSED"]);

/**
 * Which macro-stages each role acts on (drives the highlight span). ADMIN and
 * DEVELOPER have no single span — ADMIN sees all full-opacity, DEVELOPER (no
 * queue) sees all at full opacity too (nothing to emphasise).
 */
const ROLE_STAGES: Partial<Record<StaffRole, JourneyStageKey[]>> = {
  KYC_APPROVER: ["KYC"],
  CREDIT_EXECUTIVE: ["CREDIT_REVIEW"],
  CREDIT_HEAD: ["CREDIT_REVIEW"],
  DISBURSEMENT_HEAD: ["DISBURSEMENT"],
  ACCOUNTANT: ["DISBURSEMENT"],
  COLLECTION_HEAD: ["ACTIVE_REPAYMENT"],
  COLLECTION_EXECUTIVE: ["ACTIVE_REPAYMENT"],
};

export function PipelineBar({
  stats,
  role,
}: {
  stats: Partial<Record<ApplicationStatus, number>>;
  role: StaffRole;
}) {
  // Sum each status's count into its macro-stage. REJECTED / CANCELLED are
  // polymorphic (their stage needs per-app event history we don't have here) and
  // KYC_REJECTED is a terminal outcome, not pending work — all three are skipped
  // so the bar shows live pipeline load only.
  const perStage: Record<JourneyStageKey, number> = {
    STARTED: 0,
    KYC: 0,
    CREDIT_REVIEW: 0,
    DISBURSEMENT: 0,
    ACTIVE_REPAYMENT: 0,
    CLOSED: 0,
  };
  for (const [status, count] of Object.entries(stats)) {
    if (status === "REJECTED" || status === "CANCELLED" || status === "KYC_REJECTED") continue;
    perStage[stageOf(status as ApplicationStatus).stage] += count ?? 0;
  }

  const isAdmin = role === "ADMIN";
  const span = ROLE_STAGES[role]; // undefined for ADMIN / DEVELOPER
  const highlighted = (key: JourneyStageKey) => !isAdmin && !!span?.includes(key);
  const dimmed = (key: JourneyStageKey) => !isAdmin && span != null && !span.includes(key);

  return (
    <div className="overflow-x-auto">
      <ol className="flex min-w-max gap-2 lg:min-w-0">
        {STAGE_ORDER.map((key) => {
          const count = perStage[key];
          const terminal = TERMINAL_STAGES.has(key);
          return (
            <li
              key={key}
              className={cn(
                "flex min-w-[7.5rem] flex-1 flex-col rounded border bg-white p-4 shadow-sm transition",
                highlighted(key) ? "border-navy ring-1 ring-navy" : "border-line",
                dimmed(key) && "opacity-50",
                terminal && "bg-grey-50",
              )}
              aria-current={highlighted(key) ? "true" : undefined}
            >
              <div className="flex items-start justify-between gap-1">
                <span className="text-xs font-medium text-muted">{STAGE_LABELS[key]}</span>
                <InfoTooltip content={STAGE_INFO[key]} />
              </div>
              <span
                className={cn(
                  "mt-1 font-serif text-2xl font-bold",
                  terminal ? "text-muted" : "text-navy",
                )}
              >
                {count}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
