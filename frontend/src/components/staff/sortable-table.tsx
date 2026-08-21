"use client";

import * as React from "react";
import { ChevronUp, ChevronDown, ChevronsUpDown } from "lucide-react";

/**
 * Generic client-side table sort primitive for the staff console.
 *
 * Nothing like this exists elsewhere in the staff console yet — every table today renders rows in
 * whatever order the API returned them. This is deliberately loan-register-agnostic: `useTableSort`
 * takes any row array + a `keyof` and does the comparison, `SortableTh` is a plain `<th>` (so it
 * inherits whichever table's header styling — `.staff-data-table`, `table.data`, …) with a clickable
 * label + caret. Both are generic on purpose so the next staff table that needs sorting reuses them
 * rather than growing a second copy.
 */

export type SortDir = "asc" | "desc";

/** Field kinds that get a sensible *first-click* direction — dates/numbers start descending
 *  (newest / largest first, matching how staff actually scan a register), text starts ascending. */
function defaultDirFor(value: unknown): SortDir {
  if (value instanceof Date) return "desc";
  if (typeof value === "number") return "desc";
  if (typeof value === "string") {
    // ISO-ish date/timestamp strings ("2026-08-20", "2026-08-20T...") sort like dates, not text.
    if (/^\d{4}-\d{2}-\d{2}/.test(value)) return "desc";
    return "asc";
  }
  return "asc";
}

/**
 * Compares two values for sorting. `null`/`undefined` always sort last, in BOTH directions — a
 * loan's `closedOn` or `sanctionedAt` is legitimately null (not yet closed / not yet sanctioned),
 * and burying those rows at the bottom regardless of sort direction reads correctly either way
 * ("newest closed first" and "oldest closed first" both still want the *never-closed* rows last).
 */
function compareValues(a: unknown, b: unknown, dir: SortDir): number {
  const aNil = a === null || a === undefined;
  const bNil = b === null || b === undefined;
  if (aNil && bNil) return 0;
  if (aNil) return 1;
  if (bNil) return -1;

  let cmp: number;
  if (typeof a === "number" && typeof b === "number") {
    cmp = a - b;
  } else if (typeof a === "string" && typeof b === "string") {
    cmp = a.localeCompare(b);
  } else {
    cmp = String(a).localeCompare(String(b));
  }
  return dir === "asc" ? cmp : -cmp;
}

export interface UseTableSortResult<T> {
  sorted: T[];
  sortKey: string;
  dir: SortDir;
  /** Click a header: same key flips direction; a new key selects it at its default direction. */
  toggle: (key: string) => void;
  setSort: (key: string, dir: SortDir) => void;
}

/** Client-side sort over `rows` by any `keyof T`. Re-sorts on every render (cheap for register-sized
 *  tables — hundreds, not tens of thousands, of rows); callers with huge tables should memoize `rows`. */
export function useTableSort<T>(
  rows: T[],
  defaultKey: keyof T & string,
  defaultDir?: SortDir,
): UseTableSortResult<T> {
  const [sortKey, setSortKey] = React.useState<string>(defaultKey);
  const [dir, setDir] = React.useState<SortDir>(
    defaultDir ?? defaultDirFor(rows[0]?.[defaultKey]),
  );

  const toggle = React.useCallback(
    (key: string) => {
      if (key === sortKey) {
        setDir((d) => (d === "asc" ? "desc" : "asc"));
      } else {
        setSortKey(key);
        setDir(defaultDirFor((rows[0] as Record<string, unknown> | undefined)?.[key]));
      }
    },
    [sortKey, rows],
  );

  const setSort = React.useCallback((key: string, nextDir: SortDir) => {
    setSortKey(key);
    setDir(nextDir);
  }, []);

  const sorted = React.useMemo(() => {
    const copy = [...rows];
    copy.sort((a, b) =>
      compareValues((a as Record<string, unknown>)[sortKey], (b as Record<string, unknown>)[sortKey], dir),
    );
    return copy;
  }, [rows, sortKey, dir]);

  return { sorted, sortKey, dir, toggle, setSort };
}

export interface SortableThProps {
  label: string;
  sortKey: string;
  active: string;
  dir: SortDir;
  onToggle: (key: string) => void;
  className?: string;
}

/** A `<th>` with a clickable label + direction caret. Plain `<th>` on purpose — it composes with
 *  whichever ancestor table supplies the header background/uppercase/etc (`.staff-data-table`,
 *  `table.data`, …) rather than hardcoding its own colours. */
export function SortableTh({ label, sortKey, active, dir, onToggle, className }: SortableThProps) {
  const isActive = sortKey === active;
  const ariaSort: React.AriaAttributes["aria-sort"] = isActive
    ? dir === "asc"
      ? "ascending"
      : "descending"
    : "none";

  return (
    <th className={className} aria-sort={ariaSort}>
      <button
        type="button"
        onClick={() => onToggle(sortKey)}
        className="inline-flex items-center gap-1 bg-transparent p-0 font-inherit text-inherit cursor-pointer select-none"
        aria-label={`Sort by ${label}${isActive ? (dir === "asc" ? ", ascending" : ", descending") : ""}`}
      >
        <span>{label}</span>
        {isActive ? (
          dir === "asc" ? (
            <ChevronUp className="h-3 w-3" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-3 w-3" aria-hidden="true" />
          )
        ) : (
          <ChevronsUpDown className="h-3 w-3 opacity-40" aria-hidden="true" />
        )}
      </button>
    </th>
  );
}
