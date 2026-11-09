"use client";

/**
 * One owner for everything a register's view is made of: search, column filters, sort, page, size.
 *
 * Why one owner rather than six `useState`s per page, which is what the console has today: every one
 * of those pages has to remember that **changing a filter means going back to page 1**, and has to
 * do it in the same update as the filter change. Doing it in a following effect lets one request go
 * out for the new filter at the old page first — a bug this codebase has already hit and written
 * tests against on customers, leads and transactions. Here it is structural: the reducer resets the
 * page on any patch that is not the page itself, so a filter change is one transition, one render,
 * one query key, one request. It cannot be got wrong by a caller.
 *
 * The same object then feeds the table, the URL (so a filtered register is a link you can send) and
 * the export (so "Download all" carries the filters by construction, not by a second code path that
 * can drift).
 */

import * as React from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { SortDir } from "@/components/staff/sortable-table";
import type { ColumnFilter, ColumnFilterSet } from "@/lib/staff/column-filter-match";
import { parseFilters, serialiseFilters, writeFilters } from "@/lib/staff/column-filter-params";
import { readRegisterView, writeRegisterView } from "@/lib/staff/register-view-store";

export interface RegisterSort {
  key: string;
  dir: SortDir;
}

export interface RegisterState {
  q: string;
  filters: ColumnFilterSet;
  sort: RegisterSort;
  /** 1-indexed, like `PaginationBar`. */
  page: number;
  size: number;
}

export interface RegisterQueryOptions {
  /** localStorage namespace for the remembered view — "loans", "customers", "collections". */
  registerId: string;
  /** Omit (or pass undefined, while the session loads) to run in memory with no remembered view. */
  staffId?: string;
  /** Sort keys this register accepts — mirrors the server's own allow-list where there is one. */
  sortKeys: readonly string[];
  /** Filterable column keys. Anything else in the URL is ignored rather than passed on. */
  filterKeys: readonly string[];
  defaults: { sort: RegisterSort; size?: number };
  /**
   * Whether this register owns the page's query string. Default `true`.
   *
   * Pass `false` for a register that is one of SEVERAL on a page: `/staff/applications` renders
   * five `StatusQueue`s and a `RepaymentVerifyQueue` at once, and six hooks each writing `sort=`,
   * `dir=` and `f.*` into the same URL would overwrite one another every render — the last to run
   * wins, then the next render reads its rival's view back out as its own. A shared link to one
   * panel's filter is worth little anyway; those registers keep the remembered view (which IS
   * namespaced, by `registerId`) and leave the address bar to the page.
   */
  url?: boolean;
}

export interface RegisterQuery {
  state: RegisterState;
  /** The only writer. Any patch that is not `{ page }` alone returns to page 1 in the same update. */
  apply: (patch: Partial<RegisterState>) => void;
  setQ: (q: string) => void;
  setFilter: (key: string, next: ColumnFilter | undefined) => void;
  setFilters: (filters: ColumnFilterSet) => void;
  setSort: (key: string, dir: SortDir) => void;
  toggleSort: (key: string) => void;
  setPage: (page: number) => void;
  setSize: (size: number) => void;
  /** Clears search and filters; keeps sort and page size. */
  clearAll: () => void;
  activeCount: number;
  /**
   * The view as server params — filters, search and sort, **without** page/size, so it is both the
   * React Query key fragment and exactly what an "export everything matching" call should send.
   */
  queryParams: Record<string, string>;
}

const DEFAULT_SIZE = 25;

type Action =
  | { kind: "apply"; patch: Partial<RegisterState> }
  | { kind: "page"; page: number }
  | { kind: "restore"; state: RegisterState };

function reducer(state: RegisterState, action: Action): RegisterState {
  switch (action.kind) {
    case "restore":
      return action.state;
    case "page":
      return state.page === action.page ? state : { ...state, page: action.page };
    case "apply": {
      const next = { ...state, ...action.patch };
      // The rule, in one line: anything that changes WHICH rows match starts again at the first
      // page. Page 3 of the old result set is not a position in the new one.
      next.page = 1;
      return next;
    }
  }
}

function isSortDir(v: string | null): v is SortDir {
  return v === "asc" || v === "desc";
}

/** Read a whole view out of a query string, keeping only what this register accepts. */
function stateFromParams(params: URLSearchParams, opts: RegisterQueryOptions): RegisterState {
  const sortKey = params.get("sort");
  const dir = params.get("dir");
  const page = Number(params.get("page"));
  const size = Number(params.get("size"));
  return {
    q: params.get("q") ?? "",
    filters: parseFilters(params, opts.filterKeys),
    sort: {
      key: sortKey && opts.sortKeys.includes(sortKey) ? sortKey : opts.defaults.sort.key,
      dir: isSortDir(dir) ? dir : opts.defaults.sort.dir,
    },
    page: Number.isFinite(page) && page >= 1 ? Math.floor(page) : 1,
    size: Number.isFinite(size) && size >= 1 ? Math.floor(size) : (opts.defaults.size ?? DEFAULT_SIZE),
  };
}

/** Does this query string say anything about a register view at all? */
function carriesView(params: URLSearchParams): boolean {
  for (const key of params.keys()) {
    if (key.startsWith("f.") || key === "q" || key === "sort" || key === "dir" || key === "page") {
      return true;
    }
  }
  return false;
}

/** The view as a query string, with defaults left out so an untouched register keeps a clean URL. */
function viewToQueryString(current: string, state: RegisterState, opts: RegisterQueryOptions): string {
  const params = new URLSearchParams(writeFilters(current, state.filters));
  const set = (key: string, value: string, omitWhen: string) => {
    if (value === omitWhen) params.delete(key);
    else params.set(key, value);
  };
  set("q", state.q, "");
  set("sort", state.sort.key, opts.defaults.sort.key);
  set("dir", state.sort.dir, opts.defaults.sort.dir);
  set("page", String(state.page), "1");
  set("size", String(state.size), String(opts.defaults.size ?? DEFAULT_SIZE));
  return params.toString();
}

export function useRegisterQuery(opts: RegisterQueryOptions): RegisterQuery {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  // Read once, in the initialiser. The URL wins over the remembered view — a link someone sent must
  // show them what the sender saw — and the remembered view is only consulted when the URL says
  // nothing about this register. Reading it in an effect instead would flash the recipient of a
  // shared link their own saved view first, then replace it.
  const ownsUrl = opts.url !== false;

  const [state, dispatch] = React.useReducer(reducer, undefined, () => {
    if (ownsUrl && carriesView(searchParams)) {
      return stateFromParams(new URLSearchParams(searchParams.toString()), opts);
    }
    const stored = opts.staffId ? readRegisterView(opts.staffId, opts.registerId) : null;
    return stateFromParams(new URLSearchParams(stored ?? ""), opts);
  });

  /**
   * Whether the remembered view has been dealt with — restored, declined, or pre-empted by the
   * operator touching a control.
   *
   * It needs a flag because `staffId` is NOT known on the first render: it comes from the session
   * query, which resolves a tick later. So the reducer's initialiser usually cannot read the
   * remembered view, and without this the first write after the id arrives would save the DEFAULT
   * view over the saved one — wiping it on the way past.
   */
  const settledRef = React.useRef(false);

  const apply = React.useCallback((patch: Partial<RegisterState>) => {
    // An operator who starts filtering before the session resolves has said what they want; their
    // view must not be replaced by a restore landing a moment later.
    settledRef.current = true;
    dispatch({ kind: "apply", patch });
  }, []);
  const setPage = React.useCallback((page: number) => {
    settledRef.current = true;
    dispatch({ kind: "page", page });
  }, []);

  const urlCarriesView = ownsUrl && carriesView(searchParams);
  React.useEffect(() => {
    if (settledRef.current || !opts.staffId) return;
    settledRef.current = true;
    // A shared link always wins: it must show the recipient what the sender saw.
    if (urlCarriesView) return;
    const stored = readRegisterView(opts.staffId, opts.registerId);
    if (stored) dispatch({ kind: "restore", state: stateFromParams(new URLSearchParams(stored), opts) });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opts.staffId, opts.registerId, urlCarriesView]);

  const setQ = React.useCallback((q: string) => apply({ q }), [apply]);
  const setFilters = React.useCallback((filters: ColumnFilterSet) => apply({ filters }), [apply]);
  const setSize = React.useCallback((size: number) => apply({ size }), [apply]);
  const setSort = React.useCallback((key: string, dir: SortDir) => apply({ sort: { key, dir } }), [apply]);

  const setFilter = React.useCallback(
    (key: string, next: ColumnFilter | undefined) => {
      const filters = { ...state.filters };
      if (next === undefined) delete filters[key];
      else filters[key] = next;
      apply({ filters });
    },
    [apply, state.filters],
  );

  const toggleSort = React.useCallback(
    (key: string) => {
      apply({
        sort:
          key === state.sort.key
            ? { key, dir: state.sort.dir === "asc" ? "desc" : "asc" }
            : { key, dir: "desc" },
      });
    },
    [apply, state.sort],
  );

  const clearAll = React.useCallback(() => apply({ q: "", filters: {} }), [apply]);

  // Mirror into the URL and the remembered view. Replaced only when it actually differs, so an
  // untouched register adds no history entry and a poll never churns the address bar.
  // A register that does not own the URL still serialises its view — that string is the stored
  // format — it just never pushes it to the router.
  const queryString = ownsUrl
    ? viewToQueryString(searchParams.toString(), state, opts)
    : viewToQueryString("", state, opts);
  React.useEffect(() => {
    if (ownsUrl && queryString !== searchParams.toString()) {
      router.replace(queryString ? `${pathname}?${queryString}` : pathname, { scroll: false });
    }
    // Only once the remembered view has been read (or ruled out) — otherwise the default view this
    // page opened with would be written over the saved one before it could ever be restored.
    if (opts.staffId && settledRef.current) writeRegisterView(opts.staffId, opts.registerId, queryString);
    // `opts` is a fresh object each render by construction; the primitives inside it are what matter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryString, opts.staffId, opts.registerId, pathname, ownsUrl]);

  const queryParams = React.useMemo(() => {
    const out: Record<string, string> = { ...serialiseFilters(state.filters) };
    if (state.q) out.q = state.q;
    out.sort = state.sort.key;
    out.dir = state.sort.dir;
    return out;
  }, [state.filters, state.q, state.sort]);

  const activeCount =
    Object.values(state.filters).filter((f) => f !== undefined).length + (state.q ? 1 : 0);

  return {
    state,
    apply,
    setQ,
    setFilter,
    setFilters,
    setSort,
    toggleSort,
    setPage,
    setSize,
    clearAll,
    activeCount,
    queryParams,
  };
}
