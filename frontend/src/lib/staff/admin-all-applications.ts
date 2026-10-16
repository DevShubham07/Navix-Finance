/**
 * Pure helpers for the ADMIN "All applications" register (`/staff/admin/all-applications`).
 *
 * Search, column filters and completeness all run on the server (`GET /api/applications/all/page`);
 * the browser holds no copy of that filter, so it cannot drift from it.
 */

import { ApplicationApiError } from "@/lib/api/applications";

export type CompletenessFilter = "ALL" | "COMPLETE" | "INCOMPLETE";

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
