/**
 * Pure application-journey mapper (no React, no I/O).
 *
 * Collapses the backend's 20-status state machine into 6 macro-stages for the
 * staff Journey visualization (drawer + detail page). Given an application and
 * its event trail, {@link deriveJourney} yields the ordered stages with a state
 * each (done / current / upcoming / branch), plus fast-track + terminal flags.
 *
 * Kept deliberately dependency-free and exhaustive: the `switch` in
 * {@link stageOf} has a `never` guard, so adding a 21st `ApplicationStatus`
 * is a compile error until it is mapped here.
 */

import type {
  ApplicationStatus,
  ApplicationView,
  EventView,
} from "@/lib/api/applications";

/** The six macro-stages of the loan lifecycle. */
export type JourneyStageKey =
  | "STARTED"
  | "KYC"
  | "CREDIT_REVIEW"
  | "DISBURSEMENT"
  | "ACTIVE_REPAYMENT"
  | "CLOSED";

/**
 * A stage's rendered state. Happy-path progression is
 * `upcoming → pending / in_progress → done`; `active`/`overdue`/`defaulted`
 * describe the live loan; `rejected`/`cancelled`/`failed`/`written_off` are
 * branch outcomes; `skipped` marks credit review on a pre-approved fast-track.
 */
export type JourneyStageState =
  | "upcoming"
  | "pending"
  | "in_progress"
  | "done"
  | "skipped"
  | "active"
  | "overdue"
  | "defaulted"
  | "failed"
  | "rejected"
  | "cancelled"
  | "written_off";

/** One macro-stage in the derived journey. */
export interface JourneyStage {
  key: JourneyStageKey;
  label: string;
  state: JourneyStageState;
  /** Events bucketed to this stage (chronological, as supplied). */
  events: EventView[];
}

/** Canonical stage order (index = progression). */
export const STAGE_ORDER: JourneyStageKey[] = [
  "STARTED",
  "KYC",
  "CREDIT_REVIEW",
  "DISBURSEMENT",
  "ACTIVE_REPAYMENT",
  "CLOSED",
];

/** Human labels for each macro-stage. */
export const STAGE_LABELS: Record<JourneyStageKey, string> = {
  STARTED: "Started",
  KYC: "KYC",
  CREDIT_REVIEW: "Credit review",
  DISBURSEMENT: "Disbursement",
  ACTIVE_REPAYMENT: "Active & repayment",
  CLOSED: "Closed",
};

/** States that represent a bad terminal outcome (no further progression). */
const TERMINAL_BAD_STATES: ReadonlySet<JourneyStageState> = new Set([
  "rejected",
  "cancelled",
  "defaulted",
  "written_off",
]);

/**
 * Map a single status to its macro-stage + intrinsic state.
 *
 * `REJECTED` / `CANCELLED` are **polymorphic**: they carry no stage of their
 * own, so the origin is resolved from `lastFromStatus` (the `fromStatus` of the
 * terminating event). When that is unavailable, they fall back to `STARTED`.
 *
 * Exhaustive over `ApplicationStatus` (compile-time `never` guard).
 */
export function stageOf(
  status: ApplicationStatus,
  lastFromStatus?: ApplicationStatus | null,
): { stage: JourneyStageKey; state: JourneyStageState } {
  switch (status) {
    case "DRAFT":
      return { stage: "STARTED", state: "in_progress" };
    case "KYC_PENDING":
      return { stage: "KYC", state: "pending" };
    case "KYC_APPROVED":
      return { stage: "KYC", state: "done" };
    case "KYC_REJECTED":
      return { stage: "KYC", state: "rejected" };
    case "PRE_APPROVED":
      return { stage: "KYC", state: "done" };
    case "REVIEW_PENDING":
      return { stage: "KYC", state: "pending" };
    case "CREDIT_EXEC_PENDING":
      return { stage: "CREDIT_REVIEW", state: "pending" };
    case "CREDIT_EXEC_APPROVED":
      return { stage: "CREDIT_REVIEW", state: "in_progress" };
    case "CREDIT_HEAD_PENDING":
      return { stage: "CREDIT_REVIEW", state: "pending" };
    case "CREDIT_HEAD_APPROVED":
      return { stage: "CREDIT_REVIEW", state: "done" };
    case "DISBURSEMENT_PENDING":
      return { stage: "DISBURSEMENT", state: "pending" };
    case "ACCOUNTANT_PENDING":
      return { stage: "DISBURSEMENT", state: "in_progress" };
    case "DISBURSEMENT_FAILED":
      return { stage: "DISBURSEMENT", state: "failed" };
    case "DISBURSED":
      return { stage: "DISBURSEMENT", state: "done" };
    case "ACTIVE":
      return { stage: "ACTIVE_REPAYMENT", state: "active" };
    case "OVERDUE":
      return { stage: "ACTIVE_REPAYMENT", state: "overdue" };
    case "DEFAULTED":
      return { stage: "ACTIVE_REPAYMENT", state: "defaulted" };
    case "CLOSED":
      return { stage: "CLOSED", state: "done" };
    case "WRITTEN_OFF":
      return { stage: "CLOSED", state: "written_off" };
    case "REJECTED":
      return {
        stage: lastFromStatus ? stageOf(lastFromStatus).stage : "STARTED",
        state: "rejected",
      };
    case "CANCELLED":
      return {
        stage: lastFromStatus ? stageOf(lastFromStatus).stage : "STARTED",
        state: "cancelled",
      };
    default: {
      // Exhaustiveness guard — a new ApplicationStatus must be mapped above.
      const _exhaustive: never = status;
      return _exhaustive;
    }
  }
}

/**
 * True once the application has taken the pre-approved reborrow fast-track
 * (`PRE_APPROVED → DISBURSEMENT_PENDING`), skipping credit review.
 *
 * Derived from history — NOT from the transient `ApplicationView.fastTrack`
 * flag, which is only true momentarily at `DISBURSEMENT_PENDING`.
 */
export function isFastTrack(events: EventView[]): boolean {
  return events.some(
    (e) => e.fromStatus === "PRE_APPROVED" && e.toStatus === "DISBURSEMENT_PENDING",
  );
}

/**
 * Bucket events into macro-stages.
 *
 * - The creation event (`fromStatus == null`) buckets to `STARTED`.
 * - A terminating `REJECTED` / `CANCELLED` event (a **branch** event) buckets
 *   to the stage of its `fromStatus` (where the rejection/cancellation happened),
 *   not to a phantom "rejected" stage.
 * - Every other event buckets by its `toStatus`.
 */
export function bucketEvents(events: EventView[]): Record<JourneyStageKey, EventView[]> {
  const buckets: Record<JourneyStageKey, EventView[]> = {
    STARTED: [],
    KYC: [],
    CREDIT_REVIEW: [],
    DISBURSEMENT: [],
    ACTIVE_REPAYMENT: [],
    CLOSED: [],
  };

  for (const e of events) {
    let stage: JourneyStageKey;
    if (e.fromStatus == null) {
      // Creation event.
      stage = "STARTED";
    } else if (e.toStatus === "REJECTED" || e.toStatus === "CANCELLED") {
      // Branch event — attribute to where it branched from.
      stage = stageOf(e.fromStatus).stage;
    } else if (e.toStatus != null) {
      stage = stageOf(e.toStatus).stage;
    } else {
      // No target status (defensive) — attribute to the originating stage.
      stage = stageOf(e.fromStatus).stage;
    }
    buckets[stage].push(e);
  }

  return buckets;
}

/** The fully-derived journey for an application. */
export interface DerivedJourney {
  stages: JourneyStage[];
  /** Took the pre-approved reborrow fast-track (credit review skipped). */
  fastTrack: boolean;
  /** Ended in a bad terminal outcome (rejected / cancelled / defaulted / written-off). */
  isTerminalBad: boolean;
}

/** Newest event's `fromStatus` — used to resolve the polymorphic REJECTED/CANCELLED origin. */
function lastFromStatus(events: EventView[]): ApplicationStatus | null {
  let latest: EventView | null = null;
  for (const e of events) {
    if (latest == null || new Date(e.at).getTime() >= new Date(latest.at).getTime()) {
      latest = e;
    }
  }
  return latest?.fromStatus ?? null;
}

/**
 * Derive the ordered macro-stage journey for an application.
 *
 * Stages before the current one are `done` (credit review becomes `skipped`
 * on a fast-track); the current stage carries its intrinsic state from
 * {@link stageOf}; later stages are `upcoming`.
 */
export function deriveJourney(
  app: ApplicationView,
  events: EventView[],
): DerivedJourney {
  const fastTrack = isFastTrack(events);
  const current = stageOf(app.status, lastFromStatus(events));
  const currentIndex = STAGE_ORDER.indexOf(current.stage);
  const buckets = bucketEvents(events);

  const stages: JourneyStage[] = STAGE_ORDER.map((key, index) => {
    let state: JourneyStageState;
    if (index < currentIndex) {
      state = fastTrack && key === "CREDIT_REVIEW" ? "skipped" : "done";
    } else if (index === currentIndex) {
      state = current.state;
    } else {
      state = "upcoming";
    }
    return { key, label: STAGE_LABELS[key], state, events: buckets[key] };
  });

  return {
    stages,
    fastTrack,
    isTerminalBad: TERMINAL_BAD_STATES.has(current.state),
  };
}
