/**
 * Pure view logic for `/staff/performance` — kept out of the page so it can be unit-tested.
 *
 * Nothing here decides who may see what: the roster is scoped server-side
 * (`DecisionHistoryService.rosterFor`). These helpers only shape what the page renders.
 */

import { ApplicationApiError, type StaffPerformanceRow } from "@/lib/api/applications";

export type PerformanceSortDir = "asc" | "desc";

/** Every column the register can sort by — one list so URL validation and the headers can't drift. */
export const PERFORMANCE_SORT_KEYS = [
  "staffName",
  "role",
  "accepted",
  "rejected",
  "pendingNow",
  "totalActions",
  "activeDays",
  "avgTurnaroundMinutes",
  "moneyPaise",
  "callsMade",
] as const;
export type PerformanceSortKey = (typeof PERFORMANCE_SORT_KEYS)[number];

/** The register's default order; omitted from the URL, the same way `/staff/loans` omits its own. */
export const PERFORMANCE_DEFAULT_SORT: { key: PerformanceSortKey; dir: PerformanceSortDir } = {
  key: "totalActions",
  dir: "desc",
};

export function isPerformanceSortKey(v: string | null | undefined): v is PerformanceSortKey {
  return !!v && (PERFORMANCE_SORT_KEYS as readonly string[]).includes(v);
}

/** `?sort=&dir=` → a valid sort; anything unrecognised falls back to the default. */
export function parsePerformanceSort(
  sort: string | null | undefined,
  dir: string | null | undefined,
): { key: PerformanceSortKey; dir: PerformanceSortDir } {
  return {
    key: isPerformanceSortKey(sort) ? sort : PERFORMANCE_DEFAULT_SORT.key,
    dir: dir === "asc" ? "asc" : dir === "desc" ? "desc" : PERFORMANCE_DEFAULT_SORT.dir,
  };
}

/**
 * The query string with the sort written into it, every other parameter kept as-is. Default values
 * are deleted rather than written, so an untouched page keeps a clean URL. Returned normalised by
 * `URLSearchParams`, so callers can compare it with `searchParams.toString()` to skip a no-op replace.
 */
export function withPerformanceSort(current: string, key: string, dir: PerformanceSortDir): string {
  const p = new URLSearchParams(current);
  if (key === PERFORMANCE_DEFAULT_SORT.key) p.delete("sort");
  else p.set("sort", key);
  if (dir === PERFORMANCE_DEFAULT_SORT.dir) p.delete("dir");
  else p.set("dir", dir);
  return p.toString();
}

export interface PerformanceTotals {
  accepted: number;
  rejected: number;
  actions: number;
  pending: number;
  calls: number;
}

/**
 * Tile totals over the rows on screen, or `null` when there is no data to total yet. `undefined`
 * (nothing loaded) must not reduce to zeros: a 0 under "Approved" is a claim that nobody approved
 * anything, which is not what "still loading" means.
 */
export function performanceTotals(rows: readonly StaffPerformanceRow[] | undefined): PerformanceTotals | null {
  if (!rows) return null;
  return rows.reduce<PerformanceTotals>(
    (acc, r) => ({
      accepted: acc.accepted + r.accepted,
      rejected: acc.rejected + r.rejected,
      actions: acc.actions + r.totalActions,
      pending: acc.pending + r.pendingNow,
      calls: acc.calls + r.callsMade,
    }),
    { accepted: 0, rejected: 0, actions: 0, pending: 0, calls: 0 },
  );
}

/**
 * True when the server refused the caller outright — the page then shows a no-access notice instead
 * of an error with a retry, since retrying cannot help. That refusal arrives two ways: a real HTTP
 * 403 (the BFF's `forbidden()`), or the backend's role rejection, which is a `BusinessException`
 * (`FORBIDDEN_ROLE` for a DSA, `FORBIDDEN` for someone else's team) and so travels as HTTP 422.
 */
export function isPerformanceAccessDenied(error: unknown): boolean {
  if (!(error instanceof ApplicationApiError)) return false;
  return error.status === 403 || error.code === "FORBIDDEN_ROLE" || error.code === "FORBIDDEN";
}

/**
 * Why the register body is empty: `filtered` when the column filters hid every row of a non-empty
 * roster, `no-roster` when the server returned nobody at all, `null` when there are rows to show.
 * The two need different copy — "nobody matches" and "nobody exists" are different facts.
 */
export function performanceEmptyKind(rosterCount: number, visibleCount: number): "filtered" | "no-roster" | null {
  if (visibleCount > 0) return null;
  return rosterCount > 0 ? "filtered" : "no-roster";
}
