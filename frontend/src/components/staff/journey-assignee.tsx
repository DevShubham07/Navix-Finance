"use client";

/**
 * Journey-view-only "who is this with" label (staff-only).
 *
 * READ-ONLY / DISPLAY-ONLY: this component never writes anything. It shows the real, server-resolved
 * `assignedExecutiveName` when one exists; otherwise, for an application sitting in `KYC_PENDING` or
 * `KYC_APPROVED` with no real assignee yet, it shows an IMPLIED "Currently with: {Credit Head}" label
 * so a reviewer knows whose desk the file conventionally sits on before anyone has formally picked it up.
 *
 * This label is computed purely at render time from `GET /api/applications/credit-executives?role=
 * CREDIT_HEAD` (the existing assignee-picker endpoint, already readable by any staff role) — no new
 * backend endpoint, no write, no `application_event` row, no `assignedExecutiveId` mutation. The real
 * assignment flow (`POST /{id}/assign`, CREDIT_HEAD-only maker-checker) is untouched by this component;
 * the moment it runs, `assignedExecutiveName` becomes non-null and the implied wording disappears.
 *
 * Mounted in exactly two journey surfaces (ApplicationDetailDialog's Journey tab, and the
 * ApplicationJourney drawer) — never in the presentational, data-fetching-free JourneyStepper itself,
 * and never in any queue/grouping/list surface, which must stay keyed on the real assignedExecutiveId.
 */

import { useQuery } from "@tanstack/react-query";
import { staffApi, type ApplicationView, type StaffSummary } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/** Statuses where an unassigned intake conventionally sits on the Credit Head's desk (V45). */
const IMPLICIT_HEAD_STATUSES = new Set<ApplicationView["status"]>(["KYC_PENDING", "KYC_APPROVED"]);

function needsImpliedCreditHead(app: ApplicationView): boolean {
  return (
    app.assignedExecutiveName == null &&
    app.assignedExecutiveId == null &&
    IMPLICIT_HEAD_STATUSES.has(app.status)
  );
}

/** Pick the first active Credit Head from the picker list (falls back to the first row). */
function pickCreditHead(heads: StaffSummary[] | undefined): StaffSummary | null {
  if (!heads?.length) return null;
  return heads.find((h) => h.role === "CREDIT_HEAD") ?? heads[0];
}

export function JourneyAssignee({ app }: { app: ApplicationView }) {
  const implied = needsImpliedCreditHead(app);
  const headQ = useQuery({
    queryKey: ["staff-picker", "CREDIT_HEAD"],
    queryFn: () => staffApi.creditExecutives("CREDIT_HEAD"),
    staleTime: 60_000,
    enabled: implied,
  });

  let label: string;
  if (app.assignedExecutiveName) {
    label = `Currently assigned to: ${app.assignedExecutiveName}`;
  } else if (implied && headQ.isLoading) {
    label = "Checking assignee…";
  } else if (implied) {
    const head = pickCreditHead(headQ.data);
    label = head
      ? `Currently with: ${head.name} (Credit Head) — not yet formally assigned`
      : "Unassigned";
  } else {
    label = "Unassigned";
  }

  return (
    <p className="text-xs text-muted">
      {label}
      {app.currentStageEnteredAt ? ` · In this stage since ${formatDateTime(app.currentStageEnteredAt)}` : ""}
    </p>
  );
}
