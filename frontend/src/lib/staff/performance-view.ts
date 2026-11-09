/**
 * Pure view logic for `/staff/performance` — kept out of the page so it can be unit-tested.
 *
 * Nothing here decides who may see what: the roster is scoped server-side
 * (`DecisionHistoryService.rosterFor`). These helpers only shape what the page renders.
 */

import { ApplicationApiError } from "@/lib/api/applications";
import type { PerformanceEntity } from "./performance-entities";

export type PerformanceSortDir = "asc" | "desc";

/** Every column an entity's register can sort by: the name, plus each of its metrics. */
export function performanceSortKeys(entity: PerformanceEntity): string[] {
  return ["staffName", ...entity.metrics.map((m) => m.key)];
}

export function isPerformanceSortKey(v: string | null | undefined, entity: PerformanceEntity): v is string {
  return !!v && performanceSortKeys(entity).includes(v);
}

/** `?sort=&dir=` → a valid sort for this entity; anything unrecognised falls back to its default. */
export function parsePerformanceSort(
  sort: string | null | undefined,
  dir: string | null | undefined,
  entity: PerformanceEntity,
): { key: string; dir: PerformanceSortDir } {
  return {
    key: isPerformanceSortKey(sort, entity) ? sort : entity.defaultSort,
    dir: dir === "asc" ? "asc" : "desc",
  };
}

/**
 * The query string with the sort written into it, every other parameter kept as-is. The entity's
 * default (its headline metric, descending) is deleted rather than written, so an untouched page
 * keeps a clean URL. Returned normalised by `URLSearchParams`, so callers can compare it with
 * `searchParams.toString()` to skip a no-op replace.
 */
export function withPerformanceSort(
  current: string,
  key: string,
  dir: PerformanceSortDir,
  entity: PerformanceEntity,
): string {
  const p = new URLSearchParams(current);
  if (key === entity.defaultSort) p.delete("sort");
  else p.set("sort", key);
  if (dir === "desc") p.delete("dir");
  else p.set("dir", dir);
  return p.toString();
}

/**
 * The query string switched to another entity. The sort is dropped with it: each entity has its own
 * columns, so a sort on one ("interactions") means nothing on the next.
 */
export function withPerformanceEntity(current: string, entityKey: string): string {
  const p = new URLSearchParams(current);
  p.set("entity", entityKey);
  p.delete("sort");
  p.delete("dir");
  return p.toString();
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
