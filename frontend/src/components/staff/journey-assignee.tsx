"use client";

/**
 * Journey-view-only "who is this with" label (staff-only).
 *
 * READ-ONLY / DISPLAY-ONLY: this component never writes anything. It shows the real, server-resolved
 * `assignedExecutiveName` when one exists; otherwise, for an application sitting in `KYC_PENDING` with
 * no real assignee yet, it shows an IMPLIED "Currently with: {the sole active Credit Head}" label so a
 * reviewer knows whose desk the file conventionally sits on before anyone has formally picked it up.
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
import { staffApi, type ApplicationView } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

export function JourneyAssignee({ app }: { app: ApplicationView }) {
  const headQ = useQuery({
    queryKey: ["staff-picker", "CREDIT_HEAD"],
    queryFn: () => staffApi.creditExecutives("CREDIT_HEAD"),
    staleTime: 60_000,
    // Only relevant while there's no real assignee yet — skip the fetch once one exists.
    enabled: app.assignedExecutiveName == null && app.assignedExecutiveId == null,
  });

  let label: string;
  if (app.assignedExecutiveName) {
    label = `Currently assigned to: ${app.assignedExecutiveName}`;
  } else if (app.assignedExecutiveId == null && app.status === "KYC_PENDING" && (headQ.data?.length ?? 0) === 1) {
    // Exactly one active Credit Head — the user's stated premise. Degrade to "Unassigned" rather than
    // guessing if that stops being true (0 or >1 active heads).
    label = `Currently with: ${headQ.data![0].name} (Credit Head) — not yet formally assigned`;
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
