/**
 * Pure helpers for the ADMIN "All applications" register (`/staff/admin/all-applications`).
 *
 * The register fetches every application once (`GET /api/applications/all`) and filters in the
 * browser, so the filter lives here — a plain function the page can `useMemo` and a test can pin —
 * instead of an inline closure re-run on every render.
 */

import { ApplicationApiError, statusLabel, type AdminApplicationView } from "@/lib/api/applications";

export type CompletenessFilter = "ALL" | "COMPLETE" | "INCOMPLETE";

/**
 * The rows that pass the completeness filter and the free-text search.
 *
 * The search is case-insensitive and trimmed, and matches anywhere in the application id, customer
 * id, name, PAN, mobile, email or the human status label ("Kyc Pending"). A blank search matches
 * every row the completeness filter keeps. Returns a new array; the input is never mutated.
 */
export function filterAdminApplications(
  all: readonly AdminApplicationView[],
  completeness: CompletenessFilter,
  query: string,
): AdminApplicationView[] {
  const needle = query.trim().toLowerCase();
  return all.filter((a) => {
    if (completeness === "COMPLETE" && !a.complete) return false;
    if (completeness === "INCOMPLETE" && a.complete) return false;
    if (!needle) return true;
    return [a.id, a.customerId, a.fullName, a.pan, a.mobile, a.email, statusLabel(a.status)]
      .filter((v) => v != null)
      .map((v) => String(v).toLowerCase())
      .some((s) => s.includes(needle));
  });
}

/** True when either control narrows the register — what decides whether "Clear" is offered. */
export function isAdminApplicationsFilterActive(completeness: CompletenessFilter, query: string): boolean {
  return completeness !== "ALL" || query.trim() !== "";
}

/**
 * React Query `retry` for a request whose 4xx answer is final: a refusal (403 FORBIDDEN_ROLE), a
 * missing session (401) or a bad request will say the same thing a second time, so retrying only
 * delays the message. Anything else — a 5xx, or a network failure (status 0) — gets the console's
 * usual single retry. `failureCount` is 0 on the first failure, as TanStack Query passes it.
 */
export function retryUnlessClientError(failureCount: number, error: unknown): boolean {
  if (error instanceof ApplicationApiError && error.status >= 400 && error.status < 500) return false;
  return failureCount < 1;
}
