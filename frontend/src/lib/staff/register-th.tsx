"use client";

/**
 * The boilerplate remover for a filterable header row.
 *
 * `FilterableTh` needs eleven props, nine of which are the same for every column of a register —
 * the sort key and direction, the two sort callbacks, the facet lookups, the filter and its setter.
 * Written out per column that is fourteen lines each, and on a thirteen-column register it is the
 * same fourteen lines thirteen times, where the only thing a reader has to check is that `kind=`
 * and `options=` name the same column as `onApply=`. They do not always: that is a typo a type
 * checker cannot catch, because every one of those props is a string or a function of a string.
 *
 * So the column's identity is given ONCE, and its kind is read from the `FilterColumn` list that
 * already declares it — a header can no longer open a date panel over a column the matcher treats
 * as text.
 */

import * as React from "react";
import { FilterableTh, type FilterColumn } from "@/components/staff/column-filter";
import type { SortDir } from "@/components/staff/sortable-table";
import type { ColumnFilter } from "@/lib/staff/column-filter-match";
import { describeFilters } from "@/lib/staff/column-filter-params";
import type { RegisterQuery } from "@/lib/staff/register-query";
import type { ClientRegister } from "@/lib/staff/use-client-register";

export interface ThBag {
  /** The filterable columns, in declaration order — what the filter bar's "Add filter" lists. */
  keys: string[];
  sortKey: string;
  dir: RegisterQuery["state"]["sort"]["dir"];
  onToggle: (key: string) => void;
  setSort: RegisterQuery["setSort"];
  filters: RegisterQuery["state"]["filters"];
  setFilter: RegisterQuery["setFilter"];
  kindFor: (key: string) => ColumnFilter["type"];
  sortableFor: (key: string) => boolean;
  optionsFor: (key: string) => string[];
  labelsFor: (key: string) => Map<string, string>;
  headerFor: (key: string) => string;
  /** Fired once when a column's panel opens — how a server-paged register fetches its facet. */
  onOpenFacet?: (key: string) => void;
}

/** Bundle a register's view + facets + column declarations into what {@link Th} needs. */
/**
 * The direction a column takes on its FIRST click, from the kind it declares.
 *
 * `useRegisterQuery.toggleSort` opens every new column descending, which is right for a date or an
 * amount — newest and largest first is how staff scan a register — and wrong for a name, where A→Z
 * is what anyone expects. Both server-paged registers hit this and each patched `onToggle` on its
 * own `th` bag to restore the old behaviour; doing it here instead means the rule lives with the
 * `kind` it is derived from, and no page has to override a shared control to get it right. Same
 * rule as `firstClickDir` in `sortable-table.tsx`, expressed over a declared kind rather than a
 * sampled value.
 */
function firstDirFor(kind: ColumnFilter["type"]): SortDir {
  return kind === "text" || kind === "in" ? "asc" : "desc";
}

export function thBag<T>(
  rq: RegisterQuery,
  cr: Pick<ClientRegister<T>, "optionsFor" | "labelsFor">,
  columns: ReadonlyArray<FilterColumn<T>>,
  headers: Record<string, string>,
  /** Server-paged registers pass `requestFacet`; a client-side one has every value in memory. */
  onOpenFacet?: (key: string) => void,
): ThBag {
  return {
    keys: columns.map((c) => c.key),
    sortKey: rq.state.sort.key,
    dir: rq.state.sort.dir,
    onToggle: (key) => {
      if (key === rq.state.sort.key) {
        rq.setSort(key, rq.state.sort.dir === "asc" ? "desc" : "asc");
      } else {
        rq.setSort(key, firstDirFor(columns.find((c) => c.key === key)?.kind ?? "in"));
      }
    },
    setSort: rq.setSort,
    filters: rq.state.filters,
    setFilter: rq.setFilter,
    kindFor: (key) => columns.find((c) => c.key === key)?.kind ?? "in",
    sortableFor: (key) => columns.find((c) => c.key === key)?.sortable ?? true,
    optionsFor: cr.optionsFor,
    labelsFor: cr.labelsFor,
    headerFor: (key) => headers[key] ?? key,
    onOpenFacet,
  };
}

/**
 * The "Filtered: …" line for an export subtitle, in the words on screen.
 *
 * `describeFilters` takes a value resolver and nothing was passing one, so a filtered PDF named the
 * stored enum — "Reason in FRAUD_REJECTED" — which is exactly the gap between label and value this
 * whole engine exists to keep straight. The register already knows the mapping: it is the same
 * `labelsFor` the checklist reads.
 */
export function describeRegister(th: ThBag): string {
  return describeFilters(th.filters, {
    column: th.headerFor,
    value: (key, raw) => th.labelsFor(key).get(raw) ?? raw,
  });
}

/** One filterable, sortable header cell: `<Th th={th} col="status" />`. */
export function Th({ th, col, className }: { th: ThBag; col: string; className?: string }) {
  return (
    <FilterableTh
      label={th.headerFor(col)}
      sortKey={col}
      active={th.sortKey}
      dir={th.dir}
      onToggle={th.onToggle}
      setSort={th.setSort}
      kind={th.kindFor(col)}
      sortable={th.sortableFor(col)}
      options={th.optionsFor(col)}
      labels={th.labelsFor(col)}
      filter={th.filters[col]}
      onApply={(next) => th.setFilter(col, next)}
      onOpen={th.onOpenFacet ? () => th.onOpenFacet?.(col) : undefined}
      className={className}
    />
  );
}
