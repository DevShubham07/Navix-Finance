"use client";

/**
 * The client-side half of a register: rows already in memory, filtered, sorted and paged under the
 * view that {@link useRegisterQuery} owns.
 *
 * Its return shape is the one a server-paged adapter will also return, so a register migrates from
 * "fetch everything, narrow in the browser" to "ask the server for one page" by swapping which
 * adapter it calls — the table, the toolbar and the export menu above it do not change.
 *
 * `usePagination` is deliberately NOT used here: it owns its own page number, and two owners of the
 * same number is exactly the bug this hook exists to remove.
 */

import * as React from "react";
import { useColumnFilters, type FilterColumn } from "@/components/staff/column-filter";
import { sortRows } from "@/components/staff/sortable-table";
import type { RegisterQuery } from "@/lib/staff/register-query";

export interface ClientRegister<T> {
  /** Every row matching the filters, in sort order — what an export of "all matching" should take. */
  rows: T[];
  /** Just the page on screen. */
  pageRows: T[];
  total: number;
  pageCount: number;
  /** Distinct raw values still reachable for a column, given the other columns' filters. */
  optionsFor: (key: string) => string[];
  /** Raw value → the label the table shows for it. */
  labelsFor: (key: string) => Map<string, string>;
}

export function useClientRegister<T>(
  allRows: T[],
  columns: Array<FilterColumn<T>>,
  rq: RegisterQuery,
  /** Narrowing the page does itself, ahead of the column filters (a segment chip, a bucket). */
  prefilter?: (row: T) => boolean,
): ClientRegister<T> {
  const base = React.useMemo(
    () => (prefilter ? allRows.filter(prefilter) : allRows),
    [allRows, prefilter],
  );

  const controlled = React.useMemo(
    () => ({ filters: rq.state.filters, onChange: rq.setFilters }),
    [rq.state.filters, rq.setFilters],
  );
  const { filtered, optionsFor, labelsFor } = useColumnFilters(base, columns, controlled);

  const rows = React.useMemo(
    () => sortRows(filtered, rq.state.sort.key, rq.state.sort.dir),
    [filtered, rq.state.sort.key, rq.state.sort.dir],
  );

  const total = rows.length;
  const pageCount = Math.max(1, Math.ceil(total / rq.state.size));

  // Clamp rather than reset: deleting the last row of page 4 should leave you on the new last page,
  // not back at the top. A FILTER change already returned the page to 1 inside the reducer.
  const page = Math.min(Math.max(1, rq.state.page), pageCount);
  React.useEffect(() => {
    if (page !== rq.state.page) rq.setPage(page);
  }, [page, rq]);

  const pageRows = React.useMemo(() => {
    const start = (page - 1) * rq.state.size;
    return rows.slice(start, start + rq.state.size);
  }, [rows, page, rq.state.size]);

  return { rows, pageRows, total, pageCount, optionsFor, labelsFor };
}
