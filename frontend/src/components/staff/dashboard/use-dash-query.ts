"use client";

import { keepPreviousData, useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { DashParams } from "@/lib/api/applications";

/** Analytics move on the timescale of a disbursement or a repayment, not of a queue. */
export const ANALYTICS_MS = 5 * 60_000;

/**
 * One analytics query: key `["staff-dashboard-<name>", view, from, to, staffIds, ...extra]`, previous
 * data kept while a new period loads, 5-minute stale/refetch. `staff-dashboard-queue` is NOT built
 * with this (it polls every 10 s and other files invalidate that key).
 */
export function useDashQuery<T>(
  name: string,
  params: DashParams,
  extra: ReadonlyArray<string | number | boolean | null | undefined>,
  fn: () => Promise<T>,
  enabled = true,
): UseQueryResult<T> {
  return useQuery({
    queryKey: [
      `staff-dashboard-${name}`,
      params.view,
      params.from ?? "",
      params.to ?? "",
      params.staffIds?.join(",") ?? "",
      ...extra,
    ],
    queryFn: fn,
    enabled,
    placeholderData: keepPreviousData,
    staleTime: ANALYTICS_MS,
    refetchInterval: ANALYTICS_MS,
  });
}
