"use client";

/**
 * The server-paged half of a register: one page of rows, the server's count, and lazily-fetched
 * column facets, all under the view that {@link useRegisterQuery} owns.
 *
 * It returns the {@link ClientRegister} shape (plus the loading flags a network adapter needs) so a
 * register migrates from "fetch everything, narrow in the browser" to "ask the server for one page"
 * by swapping which hook it calls — the table, toolbar and export above it do not change.
 *
 * `rows` and `pageRows` are THE SAME PAGE here. On the client adapter `rows` is every matching row
 * and is what an "all matching" export takes; on this one it is not, and pretending otherwise would
 * make an export labelled "all 4,212 matching" silently carry 25. Export of everything goes through
 * `ExportMenu`'s `getAllRows` (which re-queries with `rq.queryParams`), and the menu then labels the
 * on-screen rows as "page" — see its `scope` prop.
 */

import * as React from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import type { RegisterQuery } from "@/lib/staff/register-query";
import { FILTER_PREFIX } from "@/lib/staff/column-filter-params";
import type { ClientRegister } from "@/lib/staff/use-client-register";

export interface ServerPage<T> {
  rows: T[];
  /** The server's count of every matching row — not `rows.length`. */
  total: number;
}

/**
 * A register whose page response carries more than rows and a count.
 *
 * Several of these registers show summary figures that must follow the WHOLE filter rather than the
 * page on screen — the ledger's inflow/outflow, computed by one aggregate over the same filtered
 * union — and the server already returns them beside the rows. Without somewhere to put them the
 * caller has to smuggle them out of `fetchPage` through the query cache and read them back with an
 * observer and a cast, which is a lot of machinery for three numbers and breaks the moment the key
 * changes shape. So the page type is open: whatever `fetchPage` resolves to comes back on `page`,
 * with its own type intact.
 */
export type ServerPageOf<T, P> = ServerPage<T> & P;

export interface ServerFacet {
  values: string[];
  /** The server stopped listing: this column cannot offer a complete checklist. */
  truncated: boolean;
}

export interface ServerRegisterOptions<T, P extends ServerPage<T> = ServerPage<T>> {
  /** Stable React Query key prefix, e.g. `["staff-customers"]`. Invalidate by it after a mutation. */
  key: readonly unknown[];
  /** `params` is `rq.queryParams` — filters, search, sort; page and size are passed separately. */
  fetchPage: (params: Record<string, string>, page: number, size: number) => Promise<P>;
  /** `params` already has this column's own `f.<column>` removed (the server's `spec.without(col)`). */
  fetchFacet: (column: string, params: Record<string, string>) => Promise<ServerFacet>;
  /** Raw value → label, per column, for the checklist. Absent columns show the raw value. */
  labels?: Record<string, Record<string, string>> | ((column: string) => Map<string, string>);
  /** Polling, for the registers that poll (collections). Safe: every refetch is keyed to its own view. */
  refetchInterval?: number;
  /**
   * Hold every request until this is true. Default true.
   *
   * A register behind a permission gate must not ask the server for a page before the session says
   * who is signed in — the admin registers would otherwise fire a 403 on first paint. Without it a
   * caller has to split the page into an outer gate and an inner component purely so the hooks
   * mount late, which is a lot of structure to express "not yet".
   */
  enabled?: boolean;
  /**
   * Passed to React Query for both the page and the facet queries.
   *
   * The registers behind a permission gate want "do not retry a 4xx": retrying a 403 three times
   * neither recovers nor tells the operator anything, it just delays the message.
   */
  retry?: boolean | number | ((failureCount: number, error: unknown) => boolean);
}

export interface ServerRegister<T> extends ClientRegister<T> {
  /**
   * Ask for a column's facet. Call it when that column's filter panel OPENS — `optionsFor` is called
   * by every header on every render and must never start a request, so opening is the one trigger.
   * One column is live at a time (a panel at a time); earlier columns stay readable from cache.
   */
  requestFacet: (column: string) => void;
  /** True when the server truncated this column's values; fall back to a text filter, not a dropdown. */
  facetTruncated: (column: string) => boolean;
  facetLoading: (column: string) => boolean;
  isLoading: boolean;
  /** The rows on screen belong to the PREVIOUS view; dim them and set `aria-busy`. */
  isPlaceholderData: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => void;
}

const NO_ROWS: never[] = [];
const NO_OPTIONS: string[] = [];

export function useServerRegister<T, P extends ServerPage<T> = ServerPage<T>>(
  rq: RegisterQuery,
  opts: ServerRegisterOptions<T, P>,
): ServerRegister<T> & {
  /**
   * The last page response, exactly as the server sent it — so a caller can read figures that live
   * beside the rows without a second request or a cache side-channel. `undefined` until one lands.
   */
  page: P | undefined;
} {
  const qc = useQueryClient();
  const { page, size } = rq.state;
  const params = rq.queryParams;

  // The WHOLE params object is in the key, with page and size: a refetch (and ten surfaces poll)
  // only ever lands on its own key, so a filter change cannot be answered by the previous
  // filter's in-flight request. `rq` already reset page to 1 in the same transition as the filter.
  const enabled = opts.enabled !== false;

  const pageQ = useQuery({
    queryKey: [...opts.key, "page", params, page, size],
    queryFn: () => opts.fetchPage(params, page, size),
    placeholderData: keepPreviousData,
    refetchInterval: opts.refetchInterval,
    enabled,
    ...(opts.retry === undefined ? {} : { retry: opts.retry }),
  });

  const total = pageQ.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / size));

  // Clamp rather than reset, as the client adapter does: deleting the last row of page 4 lands on
  // the new last page. Only against a settled count — a placeholder's total belongs to another view.
  React.useEffect(() => {
    if (pageQ.data && !pageQ.isPlaceholderData && page > pageCount) rq.setPage(pageCount);
  }, [pageQ.data, pageQ.isPlaceholderData, page, pageCount, rq]);

  /** The facet's own params: every active filter EXCEPT this column's, so its options respect the others. */
  const facetParams = React.useCallback(
    (column: string) => {
      const { [FILTER_PREFIX + column]: _own, ...rest } = params;
      return rest;
    },
    [params],
  );
  const facetKey = React.useCallback(
    (column: string) => [...opts.key, "facet", column, facetParams(column)] as const,
    [opts.key, facetParams],
  );

  const [active, setActive] = React.useState<string | null>(null);
  const facetQ = useQuery({
    queryKey: active ? facetKey(active) : [...opts.key, "facet", "__none__"],
    queryFn: () => opts.fetchFacet(active!, facetParams(active!)),
    // A facet is pointless before the page is allowed to load at all.
    enabled: enabled && active !== null,
    placeholderData: keepPreviousData,
    ...(opts.retry === undefined ? {} : { retry: opts.retry }),
    // Reopening the same panel is a cache read; another column's filter changing is a new key.
    staleTime: 30_000,
  });

  const facetOf = React.useCallback(
    (column: string): ServerFacet | undefined =>
      column === active
        ? facetQ.data
        : qc.getQueryData<ServerFacet>(facetKey(column)),
    [active, facetQ.data, qc, facetKey],
  );

  const optionsFor = React.useCallback((column: string) => facetOf(column)?.values ?? NO_OPTIONS, [facetOf]);
  const facetTruncated = React.useCallback((column: string) => facetOf(column)?.truncated === true, [facetOf]);
  const facetLoading = React.useCallback(
    (column: string) => column === active && facetQ.isFetching,
    [active, facetQ.isFetching],
  );

  const labelsFor = React.useCallback(
    (column: string) => {
      const l = opts.labels;
      if (typeof l === "function") return l(column);
      return new Map(Object.entries(l?.[column] ?? {}));
    },
    [opts.labels],
  );

  const rows = pageQ.data?.rows ?? (NO_ROWS as T[]);
  return {
    rows,
    pageRows: rows,
    // The whole response, so a figure the server computed beside the rows — a total over the entire
    // filter rather than the page — is readable without a second request.
    page: pageQ.data,
    total,
    pageCount,
    optionsFor,
    labelsFor,
    requestFacet: setActive,
    facetTruncated,
    facetLoading,
    isLoading: pageQ.isLoading,
    isPlaceholderData: pageQ.isPlaceholderData,
    isError: pageQ.isError,
    error: pageQ.error,
    refetch: () => void pageQ.refetch(),
  };
}
