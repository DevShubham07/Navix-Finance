"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import {
  ArrowDownAZ,
  ArrowUpAZ,
  Check,
  ChevronDown,
  ChevronUp,
  ChevronsUpDown,
  FilterX,
  Search,
} from "lucide-react";
import type { SortDir } from "./sortable-table";
import { rangeFor, toLocalISO } from "./pipeline/queue-date-filter";
import {
  BLANK,
  matchesFilter,
  normaliseFilter,
  type ColumnFilter,
  type ColumnFilterSet,
  type FilterCell,
  type TextOp,
} from "@/lib/staff/column-filter-match";

export type { ColumnFilter, ColumnFilterSet } from "@/lib/staff/column-filter-match";
export { BLANK } from "@/lib/staff/column-filter-match";

/**
 * Excel-style AutoFilter for the staff console's tables.
 *
 * `SortableTh` gives a column a sort caret and nothing else, so narrowing a register to one person
 * meant typing their name into a free-text box next to the table — fine for a name you remember
 * exactly, useless for picking one of fifty off a roster. This is the header dropdown people already
 * know from a spreadsheet: sort, then filter by the values actually present, by a range, or by a
 * text operator, with OK/Cancel.
 *
 * Three behaviours are deliberately Excel's rather than simpler alternatives:
 *  - **Edits are staged.** Ticking boxes changes nothing until OK; Cancel discards. Filtering a long
 *    list one re-render per click would make the rows jump under the cursor being clicked.
 *  - **The value list respects the OTHER columns' filters** (`optionsFor` below). Once Role is
 *    narrowed to Credit Executive, the Staff dropdown offers only Credit Executives — offering names
 *    that can no longer match would hand the reader empty tables.
 *  - **A range column still narrows everyone else.** Set Amount ≥ ₹50,000 and the Status dropdown
 *    stops offering statuses no large loan has.
 *
 * What it deliberately does NOT copy is Excel's nested "Text Filters ▸" submenu: a submenu needs
 * hover intent, a second portal layer and keyboard trapping across two panels, where a `<select>`
 * plus an input gives the same three operators in one tab stop.
 */

/**
 * One filterable column: how to read its value off a row, and what kind of filter it offers.
 *
 * `value` is the LABEL — exactly what the cell renders, because the dropdown lists what is on
 * screen. `raw` is the STORED value the filter actually carries, and it matters more than it looks:
 * almost every enum in this console renders through a label map (`T0_T7` → "1–7 DPD", settlements'
 * `PROPOSED` → "Pending"), a label is not what a server can filter on, and at least one map is
 * **lossy** — the repayment queue renders both `BANK_TRANSFER` and `NACH` as "Bank transfer", so a
 * checklist keyed on the label would silently merge two different payment methods into one tick.
 * Columns whose label is the value can leave `raw` out.
 */
export interface FilterColumn<T> {
  key: string;
  /** Exactly what the cell renders. */
  value: (row: T) => string;
  /** The stored value behind the label. Defaults to `value`. */
  raw?: (row: T) => string;
  /** Which panel this column offers. Default `"in"` — the searchable checklist. */
  kind?: ColumnFilter["type"];
  /** `num` columns: the comparable in DISPLAY units (rupees, days) — not paise. */
  num?: (row: T) => number | null | undefined;
  /** `date` columns: the IST day as `yyyy-mm-dd` (see `istDay`). */
  date?: (row: T) => string | null | undefined;
  /** Server-paged registers: the value list comes from the facets endpoint, not from the rows. */
  facet?: boolean;
  /**
   * Whether this column can be sorted. Default `true`.
   *
   * `false` renders the header as plain text with only the filter caret beside it. It exists because
   * a server-paged register may have no sort for a column — the admin register answers in id order
   * and nothing else — and a header that shows a sort chevron, responds to a click, and changes
   * nothing is worse than a header that never offered. Declared here, next to `kind`, so it cannot
   * drift from the column it describes.
   */
  sortable?: boolean;
}

export interface UseColumnFiltersResult<T> {
  /** `rows` with every column's filter applied. */
  filtered: T[];
  /**
   * The allow-list for a checklist column, or `undefined` when it isn't filtered. Kept for callers
   * written before the other kinds existed; `filterFor` is the general form.
   */
  selectionFor: (key: string) => string[] | undefined;
  /** This column's filter, whatever kind it is. */
  filterFor: (key: string) => ColumnFilter | undefined;
  /** Every active filter, for serialising into the URL or a server query. */
  filters: ColumnFilterSet;
  /** Distinct RAW values still reachable for a column, given the other columns' filters. */
  optionsFor: (key: string) => string[];
  /** Raw value → the label the table shows for it, so the checklist reads like the screen. */
  labelsFor: (key: string) => Map<string, string>;
  /** Commit a column's filter. An array is taken as a checklist; `undefined` clears it. */
  setFilter: (key: string, next: string[] | ColumnFilter | undefined) => void;
  clearAll: () => void;
  /** How many columns are currently filtered — for the "Clear filters" affordance. */
  activeCount: number;
}

/** A checklist filter, or `undefined` when the argument was already a filter or a clear. */
function asFilter(next: string[] | ColumnFilter | undefined): ColumnFilter | undefined {
  if (next === undefined) return undefined;
  return normaliseFilter(Array.isArray(next) ? { type: "in", values: next } : next);
}

/**
 * Client-side multi-column filtering. `columns` must be referentially stable across renders (wrap it
 * in `useMemo`) — it carries accessor closures, so a fresh array each render would rebuild every
 * memo here on every keystroke elsewhere on the page.
 */
export function useColumnFilters<T>(
  rows: T[],
  columns: Array<FilterColumn<T>>,
  /**
   * Hand the filter state to an owner outside this hook — `useRegisterQuery`, which also keeps it in
   * the URL and resets the page alongside it. Omitted, the hook holds its own state, which is what a
   * page with nothing else to coordinate wants.
   */
  controlled?: { filters: ColumnFilterSet; onChange: (next: ColumnFilterSet) => void },
): UseColumnFiltersResult<T> {
  const [ownFilters, setOwnFilters] = React.useState<ColumnFilterSet>({});
  const filters = controlled?.filters ?? ownFilters;
  const setFilters = React.useCallback(
    (update: (prev: ColumnFilterSet) => ColumnFilterSet) => {
      if (controlled) controlled.onChange(update(controlled.filters));
      else setOwnFilters(update);
    },
    [controlled],
  );

  const byKey = React.useMemo(() => {
    const m = new Map<string, FilterColumn<T>>();
    for (const c of columns) m.set(c.key, c);
    return m;
  }, [columns]);

  const cellOf = React.useCallback((col: FilterColumn<T>, row: T): FilterCell => {
    const text = col.value(row);
    return {
      text,
      raw: col.raw ? col.raw(row) : text,
      num: col.num?.(row),
      date: col.date?.(row),
    };
  }, []);

  /** Apply every active filter except one column's — the basis for both `filtered` and `optionsFor`. */
  const applyExcept = React.useCallback(
    (exceptKey: string | null): T[] => {
      const active = Object.entries(filters).filter(
        ([key, f]) => f !== undefined && key !== exceptKey && byKey.has(key),
      ) as Array<[string, ColumnFilter]>;
      if (active.length === 0) return rows;
      const pairs = active.map(([key, f]) => [byKey.get(key)!, f] as const);
      return rows.filter((row) => pairs.every(([col, f]) => matchesFilter(f, cellOf(col, row))));
    },
    [rows, filters, byKey, cellOf],
  );

  const filtered = React.useMemo(() => applyExcept(null), [applyExcept]);

  const optionsFor = React.useCallback(
    (key: string): string[] => {
      const col = byKey.get(key);
      if (!col) return [];
      const labels = new Map<string, string>();
      for (const row of applyExcept(key)) {
        const cell = cellOf(col, row);
        labels.set(cell.raw === "" ? BLANK : cell.raw, cell.text);
      }
      // Sorted by what the reader sees, not by the stored value — a list ordered by enum name reads
      // as random to anyone looking at labels.
      return [...labels.keys()].sort((a, b) =>
        (labels.get(a) ?? a).localeCompare(labels.get(b) ?? b, undefined, { sensitivity: "base" }),
      );
    },
    [applyExcept, byKey, cellOf],
  );

  const labelsFor = React.useCallback(
    (key: string): Map<string, string> => {
      const col = byKey.get(key);
      const out = new Map<string, string>();
      if (!col) return out;
      for (const row of rows) {
        const cell = cellOf(col, row);
        out.set(cell.raw === "" ? BLANK : cell.raw, cell.text);
      }
      return out;
    },
    [rows, byKey, cellOf],
  );

  const filterFor = React.useCallback((key: string) => filters[key], [filters]);

  const selectionFor = React.useCallback(
    (key: string) => {
      const f = filters[key];
      return f?.type === "in" ? f.values : undefined;
    },
    [filters],
  );

  const setFilter = React.useCallback((key: string, next: string[] | ColumnFilter | undefined) => {
    const f = asFilter(next);
    setFilters((prev) => {
      const out = { ...prev };
      if (f === undefined) delete out[key];
      else out[key] = f;
      return out;
    });
  }, [setFilters]);

  const clearAll = React.useCallback(() => setFilters(() => ({})), [setFilters]);

  const activeCount = Object.values(filters).filter((v) => v !== undefined).length;

  return {
    filtered,
    selectionFor,
    filterFor,
    filters,
    optionsFor,
    labelsFor,
    setFilter,
    clearAll,
    activeCount,
  };
}

/* ------------------------------------------------------------------------- */

const PANEL_WIDTH = 248;
/**
 * Above `.modal-overlay` and `Drawer` (both 200), below the toaster (300).
 *
 * It sat at 60 for as long as it had one consumer, none of it inside a dialog. Any table in
 * `ApplicationDetailDialog` or `RecordsDrawer` would have opened a filter painted underneath the
 * overlay — present, focusable, invisible.
 */
const PANEL_Z = 210;
/** Below this the panel is a bottom sheet: 248px is two thirds of a 375px screen. */
const COMPACT_MAX_WIDTH = 640;

const DATE_PRESETS: Array<{ label: string; range: () => { from?: string; to?: string } }> = [
  { label: "Today", range: () => rangeFor("TODAY", {}) },
  { label: "Yesterday", range: () => rangeFor("YESTERDAY", {}) },
  {
    label: "Last 7 days",
    range: () => {
      const now = new Date();
      return {
        from: toLocalISO(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6)),
        to: toLocalISO(now),
      };
    },
  },
  {
    label: "This month",
    range: () => {
      const now = new Date();
      return {
        from: toLocalISO(new Date(now.getFullYear(), now.getMonth(), 1)),
        to: toLocalISO(now),
      };
    },
  },
  {
    label: "Last month",
    range: () => {
      const now = new Date();
      return {
        from: toLocalISO(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
        to: toLocalISO(new Date(now.getFullYear(), now.getMonth(), 0)),
      };
    },
  },
];

const TEXT_OP_LABEL: Record<TextOp, string> = {
  contains: "Contains",
  starts: "Begins with",
  eq: "Equals",
};

export interface PanelProps {
  label: string;
  kind: ColumnFilter["type"];
  anchor: DOMRect;
  options: string[];
  labels?: Map<string, string>;
  filter: ColumnFilter | undefined;
  onApply: (next: ColumnFilter | undefined) => void;
  /**
   * Omit it and the panel renders no sort rows. A filter chip above the table is not a column
   * header, and for a `sortable: false` column it would offer a sort that does nothing — so "no
   * callback" is the signal, not a `showSort` flag that has to be kept in step with it.
   */
  onSort?: (dir: SortDir) => void;
  onClose: () => void;
  /** The id of the trapped surface this panel belongs to, when it was opened inside one. */
  trapOwner?: string;
}

/** The dropdown body. Rendered into a portal — see `FilterableTh` and `useFilterAnchor`. */
export function FilterPanel({
  label,
  kind,
  anchor,
  options,
  labels,
  filter,
  onApply,
  onSort,
  onClose,
  trapOwner,
}: PanelProps) {
  const ref = React.useRef<HTMLDivElement>(null);
  const [search, setSearch] = React.useState("");

  // A touch device gets no autofocus and no close-on-resize: the virtual keyboard opening fires
  // `resize`, so the panel used to dismiss itself the instant its own search box focused.
  const compact =
    typeof window !== "undefined"
      && (window.innerWidth < COMPACT_MAX_WIDTH || window.matchMedia("(pointer: coarse)").matches);

  // Staged drafts, one per kind. `filter === undefined` (unfiltered) opens the checklist with
  // everything ticked, which is what the reader sees in the table and therefore what they expect.
  const [ticked, setTicked] = React.useState<Set<string>>(
    () => new Set(filter?.type === "in" ? filter.values : options),
  );

  /**
   * Whether the operator has ticked anything yet, so a late-arriving value list cannot overwrite it.
   */
  const touched = React.useRef(false);

  /**
   * Keep an untouched, unfiltered checklist in step with its values.
   *
   * On a client-side register `options` is known at mount, so the initialiser above is the whole
   * story. On a SERVER-paged one the values come from the facets endpoint, which is only asked once
   * this panel opens — so at mount `options` is empty, nothing is ticked, and because OK is disabled
   * on an empty checklist the panel is unusable on first open. It also quietly contradicts the
   * convention the rest of the console teaches: everything ticked, untick what you do not want.
   *
   * So while the column is unfiltered and the operator has not touched a box, the draft follows the
   * values in. Keyed on the joined values rather than the array, because `optionsFor` returns a
   * fresh array every render and an identity-keyed effect would loop forever.
   */
  const optionsKey = options.join("\u0000");
  React.useEffect(() => {
    if (touched.current || filter?.type === "in") return;
    setTicked(new Set(options));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [optionsKey, filter?.type]);
  const [min, setMin] = React.useState(filter?.type === "num" && filter.min != null ? String(filter.min) : "");
  const [max, setMax] = React.useState(filter?.type === "num" && filter.max != null ? String(filter.max) : "");
  const [from, setFrom] = React.useState(filter?.type === "date" ? (filter.from ?? "") : "");
  const [to, setTo] = React.useState(filter?.type === "date" ? (filter.to ?? "") : "");
  const [op, setOp] = React.useState<TextOp>(filter?.type === "text" ? filter.op : "contains");
  const [term, setTerm] = React.useState(filter?.type === "text" ? filter.q : "");

  // A column can give its empty value a name of its own — "Unallocated" reads better than "(blank)"
  // on an officer column — so a supplied label always wins over the generic fallback.
  const labelOf = React.useCallback(
    (raw: string) => labels?.get(raw) || (raw === BLANK || raw === "" ? "(blank)" : raw),
    [labels],
  );

  const visible = React.useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return options;
    return options.filter((o) => labelOf(o).toLowerCase().includes(needle));
  }, [options, search, labelOf]);

  const allVisibleChecked = visible.length > 0 && visible.every((o) => ticked.has(o));

  const toggleValue = (value: string) => {
    touched.current = true;
    setTicked((prev) => {
      const next = new Set(prev);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });
  };

  /** Select All applies to what the search has narrowed to — Excel's behaviour, and the only one
   *  that lets someone search "cred", tick all, and keep their earlier picks. */
  const toggleAllVisible = () => {
    touched.current = true;
    setTicked((prev) => {
      const next = new Set(prev);
      if (allVisibleChecked) for (const o of visible) next.delete(o);
      else for (const o of visible) next.add(o);
      return next;
    });
  };

  const minNum = min.trim() === "" ? undefined : Number(min);
  const maxNum = max.trim() === "" ? undefined : Number(max);
  const rangeInverted =
    minNum != null && maxNum != null && Number.isFinite(minNum) && Number.isFinite(maxNum) && minNum > maxNum;
  const datesInverted = !!from && !!to && from > to;

  const okDisabled =
    (kind === "in" && ticked.size === 0)
    || (kind === "num" && rangeInverted)
    || (kind === "date" && datesInverted);

  const commit = () => {
    if (okDisabled) return;
    switch (kind) {
      case "in":
        // Everything ticked is the same as no filter — store it as such so the column stops showing
        // as filtered and `optionsFor` on other columns stops narrowing against a no-op.
        onApply(ticked.size === options.length ? undefined : { type: "in", values: [...ticked] });
        break;
      case "num":
        onApply(
          normaliseFilter({
            type: "num",
            min: Number.isFinite(minNum) ? minNum : undefined,
            max: Number.isFinite(maxNum) ? maxNum : undefined,
          }),
        );
        break;
      case "date":
        onApply(normaliseFilter({ type: "date", from: from || undefined, to: to || undefined }));
        break;
      case "text":
        onApply(normaliseFilter({ type: "text", op, q: term }));
        break;
    }
    onClose();
  };

  React.useEffect(() => {
    const inside = (target: EventTarget | null) =>
      !!ref.current && target instanceof Node && ref.current.contains(target);

    const onDown = (e: MouseEvent) => {
      if (!inside(e.target)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      // Stop here, or a panel opened over a Dialog closes the Dialog with it. Both `Dialog` and
      // `Drawer` also check they are the last `[aria-modal="true"]` before acting, which this panel
      // now is — belt and braces, because that check is not ours to rely on.
      e.stopPropagation();
      onClose();
    };
    // The panel is positioned off a one-time snapshot of the header's rect, so anything that moves
    // the header invalidates it — close rather than chase. Scrolling the value list is NOT that: it
    // fires a scroll event whose target is the list itself, and closing on it would dismiss the
    // panel the moment anyone scrolled to the name they came for. On a touch device neither applies:
    // the sheet is pinned to the bottom, momentum scroll fires long after the finger lifts, and
    // `resize` is what the keyboard opening looks like.
    const onScroll = (e: Event) => {
      if (!inside(e.target)) onClose();
    };

    // Capture, so a click dismisses the panel before any row handler underneath it runs.
    document.addEventListener("mousedown", onDown, true);
    document.addEventListener("keydown", onKey, true);
    if (!compact) {
      window.addEventListener("scroll", onScroll, true);
      window.addEventListener("resize", onClose);
    }
    return () => {
      document.removeEventListener("mousedown", onDown, true);
      document.removeEventListener("keydown", onKey, true);
      window.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("resize", onClose);
    };
  }, [onClose, compact]);

  // Clamp into the viewport: these headers sit inside a horizontal scroller and the last columns
  // are hard against the right edge, where an unclamped panel would hang off-screen.
  const left = Math.max(
    8,
    Math.min(anchor.left, (typeof window !== "undefined" ? window.innerWidth : PANEL_WIDTH) - PANEL_WIDTH - 8),
  );

  // Vertical placement needs the rendered height, so it is measured rather than estimated: a table
  // header sitting low on screen (charts and stat tiles above it, which is the normal layout here)
  // would otherwise push OK/Cancel below the fold. `kind` is in the deps because a date panel with
  // six presets is markedly taller than a checklist — without it the measurement is the old body's.
  const [top, setTop] = React.useState<number | null>(null);
  React.useLayoutEffect(() => {
    if (compact) {
      setTop(null);
      return;
    }
    const el = ref.current;
    if (!el) return;
    const h = el.offsetHeight;
    const vh = window.innerHeight;
    const below = anchor.bottom + 4;
    if (below + h <= vh - 8) setTop(below);
    else {
      const above = anchor.top - 4 - h;             // flip over the header
      setTop(above >= 8 ? above : Math.max(8, vh - 8 - h));
    }
  }, [anchor, options.length, kind, compact]);

  const placement: React.CSSProperties = compact
    ? { position: "fixed", left: 0, right: 0, bottom: 0, width: "auto", maxHeight: "85dvh", zIndex: PANEL_Z }
    : {
        position: "fixed",
        top: top ?? anchor.bottom + 4,
        left,
        width: PANEL_WIDTH,
        zIndex: PANEL_Z,
        // Final guard for a viewport too short for either placement — the list flexes away
        // (`min-h-0` below) so the action row survives.
        maxHeight: "calc(100dvh - 16px)",
        visibility: top == null ? "hidden" : "visible",
      };

  return (
    <div
      ref={ref}
      role="dialog"
      aria-modal="true"
      aria-label={`Filter and sort ${label}`}
      data-trap-owner={trapOwner}
      style={placement}
      className={`flex flex-col overflow-hidden border border-line bg-white text-ink shadow-xl ${
        compact ? "rounded-t-xl" : "rounded-lg"
      }`}
    >
      {onSort && (
        <div className="flex shrink-0 flex-col py-1 text-xs">
          <button type="button" onClick={() => { onSort?.("asc"); onClose(); }} className="flex items-center gap-2 px-3 py-1.5 text-left hover:bg-grey-100">
            <ArrowDownAZ size={14} className="text-muted" />
            {kind === "num" ? "Sort smallest to largest" : kind === "date" ? "Sort oldest to newest" : "Sort A to Z"}
          </button>
          <button type="button" onClick={() => { onSort?.("desc"); onClose(); }} className="flex items-center gap-2 px-3 py-1.5 text-left hover:bg-grey-100">
            <ArrowUpAZ size={14} className="text-muted" />
            {kind === "num" ? "Sort largest to smallest" : kind === "date" ? "Sort newest to oldest" : "Sort Z to A"}
          </button>
        </div>
      )}

      <div className="shrink-0 border-t border-line py-1">
        <button
          type="button"
          disabled={filter === undefined}
          onClick={() => { onApply(undefined); onClose(); }}
          className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-grey-100 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
        >
          <FilterX size={14} className="text-muted" /> Clear filter from &ldquo;{label}&rdquo;
        </button>
      </div>

      <div className="flex min-h-0 flex-1 flex-col border-t border-line p-2">
        {kind === "in" && (
          <>
            <div className="relative mb-2 shrink-0">
              <Search size={13} className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted" />
              <input
                autoFocus={!compact}
                type="text"
                aria-label={`Search ${label} values`}
                placeholder="Search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-full rounded border border-line py-1 pl-7 pr-2 text-xs text-ink outline-none focus:border-navy"
              />
            </div>

            <div className="max-h-52 min-h-0 flex-1 overflow-y-auto rounded border border-line">
              {visible.length === 0 ? (
                <p className="px-2 py-3 text-center text-xs text-muted">No matches</p>
              ) : (
                <>
                  <label className="flex cursor-pointer items-center gap-2 border-b border-line px-2 py-1.5 text-xs font-semibold hover:bg-grey-100">
                    <input
                      type="checkbox"
                      checked={allVisibleChecked}
                      onChange={toggleAllVisible}
                      className="h-3.5 w-3.5 accent-navy"
                    />
                    (Select All)
                  </label>
                  {visible.map((o) => (
                    <label key={o} className="flex cursor-pointer items-center gap-2 px-2 py-1.5 text-xs hover:bg-grey-100">
                      <input
                        type="checkbox"
                        checked={ticked.has(o)}
                        onChange={() => toggleValue(o)}
                        className="h-3.5 w-3.5 accent-navy"
                      />
                      <span className="truncate" title={labelOf(o)}>{labelOf(o)}</span>
                    </label>
                  ))}
                </>
              )}
            </div>
          </>
        )}

        {kind === "num" && (
          <div className="flex flex-col gap-2">
            <label className="flex flex-col gap-1 text-xs text-muted">
              Minimum
              <input
                autoFocus={!compact}
                type="number"
                inputMode="decimal"
                value={min}
                onChange={(e) => setMin(e.target.value)}
                placeholder="No minimum"
                className="rounded border border-line px-2 py-1 text-xs text-ink outline-none focus:border-navy"
              />
            </label>
            <label className="flex flex-col gap-1 text-xs text-muted">
              Maximum
              <input
                type="number"
                inputMode="decimal"
                value={max}
                onChange={(e) => setMax(e.target.value)}
                placeholder="No maximum"
                className="rounded border border-line px-2 py-1 text-xs text-ink outline-none focus:border-navy"
              />
            </label>
            {rangeInverted && <p className="text-xs text-error-700">Minimum is above the maximum.</p>}
          </div>
        )}

        {kind === "date" && (
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap gap-1">
              {DATE_PRESETS.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  onClick={() => {
                    const r = p.range();
                    setFrom(r.from ?? "");
                    setTo(r.to ?? "");
                  }}
                  className="rounded border border-line px-2 py-0.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
                >
                  {p.label}
                </button>
              ))}
              <button
                type="button"
                onClick={() => { setFrom(""); setTo(""); }}
                className="rounded border border-line px-2 py-0.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
              >
                All time
              </button>
            </div>
            <label className="flex flex-col gap-1 text-xs text-muted">
              From
              <input
                type="date"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
                className="rounded border border-line px-2 py-1 text-xs text-ink outline-none focus:border-navy"
              />
            </label>
            <label className="flex flex-col gap-1 text-xs text-muted">
              To
              <input
                type="date"
                value={to}
                onChange={(e) => setTo(e.target.value)}
                className="rounded border border-line px-2 py-1 text-xs text-ink outline-none focus:border-navy"
              />
            </label>
            {datesInverted && <p className="text-xs text-error-700">The start is after the end.</p>}
          </div>
        )}

        {kind === "text" && (
          <div className="flex flex-col gap-2">
            <select
              value={op}
              onChange={(e) => setOp(e.target.value as TextOp)}
              aria-label={`How to match ${label}`}
              className="rounded border border-line px-2 py-1 text-xs text-ink outline-none focus:border-navy"
            >
              {(Object.keys(TEXT_OP_LABEL) as TextOp[]).map((o) => (
                <option key={o} value={o}>{TEXT_OP_LABEL[o]}</option>
              ))}
            </select>
            <input
              autoFocus={!compact}
              type="text"
              value={term}
              onChange={(e) => setTerm(e.target.value)}
              aria-label={`${label} contains`}
              placeholder="Type a value"
              className="rounded border border-line px-2 py-1 text-xs text-ink outline-none focus:border-navy"
            />
          </div>
        )}
      </div>

      <div className="flex shrink-0 justify-end gap-2 border-t border-line px-2 py-2">
        <button type="button" onClick={onClose} className="rounded border border-line px-3 py-1 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink">
          Cancel
        </button>
        <button
          type="button"
          onClick={commit}
          disabled={okDisabled}
          className="rounded bg-navy px-3 py-1 text-xs font-semibold text-white hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Apply
        </button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------------- */

export interface FilterableThProps {
  label: string;
  sortKey: string;
  /** Currently-sorted key + direction, from `useTableSort`. */
  active: string;
  dir: SortDir;
  onToggle: (key: string) => void;
  setSort: (key: string, dir: SortDir) => void;
  /** Which panel to open. Default `"in"`. */
  kind?: ColumnFilter["type"];
  /** Raw values for the checklist (`optionsFor`). Ignored by the other kinds. */
  options?: string[];
  /** Raw → label, so the checklist reads like the table (`labelsFor`). */
  labels?: Map<string, string>;
  filter: ColumnFilter | undefined;
  onApply: (next: ColumnFilter | undefined) => void;
  /** `false` drops the sort control and its chevron, leaving the label and the filter caret. */
  sortable?: boolean;
  /**
   * Fired when the panel goes from closed to open — once per open, not on close and not on a
   * re-render while already open.
   *
   * It exists for the server-paged registers: their checklist values come from a facet endpoint,
   * and fetching those with the page would charge every visitor one `GROUP BY` per faceted column
   * to fill dropdowns nobody opened. `options` cannot be the trigger, because it is read on every
   * render. So the open is. (`ExportMenu` has the same prop for the same reason.)
   */
  onOpen?: () => void;
  className?: string;
}

/**
 * The open/anchor/portal/trap glue every filter-panel opener shares: `FilterableTh` (the header
 * caret) and `FilterBar` (the chips). Kept in one place because the `trapOwner` capture is subtle and
 * has a bug history, and two copies are how it drifts.
 */
export function useFilterAnchor() {
  const [anchor, setAnchor] = React.useState<DOMRect | null>(null);
  const [trapOwner, setTrapOwner] = React.useState<string | undefined>(undefined);

  const open = (el: HTMLElement | null, onOpen?: () => void) => {
    setTrapOwner(el?.closest<HTMLElement>('[aria-modal="true"]')?.id || undefined);
    setAnchor(el?.getBoundingClientRect() ?? null);
    // Deliberately here rather than inside a state updater: React may run an updater twice under
    // StrictMode, and this fires a fetch — it must happen once per open, not once per render pass.
    onOpen?.();
  };
  const close = () => setAnchor(null);

  /** The portalled panel, or null while closed. */
  const panel = (props: Omit<PanelProps, "anchor" | "trapOwner" | "onClose">) =>
    anchor
      ? createPortal(
          <FilterPanel {...props} anchor={anchor} trapOwner={trapOwner} onClose={close} />,
          document.body,
        )
      : null;

  return { isOpen: anchor != null, open, close, panel };
}

/**
 * A `<th>` that sorts on the label (like `SortableTh`) and opens the AutoFilter on the caret button.
 *
 * The panel is portalled to `document.body` rather than positioned inside the cell: these tables
 * live in `.staff-table-scroll` (`overflow-x: auto`, which computes `overflow-y` to `auto` as well),
 * so an absolutely-positioned dropdown would be clipped at the header's bottom edge instead of
 * overlaying the rows. It stays on `body` even inside a Dialog — a Drawer's panel animates with
 * `transition-transform`, and a transformed ancestor becomes the containing block for `position:
 * fixed`, which would re-anchor the panel to the drawer instead of the viewport. Reaching the focus
 * trap is handled by `data-trap-owner` instead, which `useFocusTrap` resolves back to its owner.
 */
export function FilterableTh({
  label,
  sortKey,
  active,
  dir,
  onToggle,
  setSort,
  kind = "in",
  options,
  labels,
  filter,
  onApply,
  sortable = true,
  onOpen,
  className,
}: FilterableThProps) {
  const isActive = sortKey === active;
  const isFiltered = filter !== undefined;
  const btnRef = React.useRef<HTMLButtonElement>(null);
  const { isOpen, open, close, panel } = useFilterAnchor();

  // A column that cannot sort carries no `aria-sort` at all: "none" tells a screen reader the
  // column IS sortable and simply isn't sorted right now.
  const ariaSort: React.AriaAttributes["aria-sort"] = !sortable
    ? undefined
    : isActive
      ? dir === "asc" ? "ascending" : "descending"
      : "none";

  return (
    <th scope="col" className={className} aria-sort={ariaSort}>
      <div className="flex items-center gap-1">
        {sortable ? (
          <button
            type="button"
            onClick={() => onToggle(sortKey)}
            className="inline-flex items-center gap-1 bg-transparent p-0 font-inherit text-inherit cursor-pointer select-none"
            aria-label={`Sort by ${label}${isActive ? (dir === "asc" ? ", ascending" : ", descending") : ""}`}
          >
            <span>{label}</span>
            {isActive ? (
              dir === "asc" ? <ChevronUp className="h-3 w-3" aria-hidden="true" />
                : <ChevronDown className="h-3 w-3" aria-hidden="true" />
            ) : (
              <ChevronsUpDown className="h-3 w-3 opacity-40" aria-hidden="true" />
            )}
          </button>
        ) : (
          <span>{label}</span>
        )}

        <button
          ref={btnRef}
          type="button"
          onClick={() => (isOpen ? close() : open(btnRef.current, onOpen))}
          aria-haspopup="dialog"
          aria-expanded={isOpen}
          aria-label={`Filter ${label}${isFiltered ? " (filtered)" : ""}`}
          className={`inline-flex items-center justify-center rounded border px-0.5 py-0.5 transition ${
            isFiltered
              ? "border-gold bg-gold text-white"
              : "border-white/30 text-inherit opacity-70 hover:opacity-100"
          }`}
        >
          {isFiltered ? <Check className="h-3 w-3" aria-hidden="true" /> : <ChevronDown className="h-3 w-3" aria-hidden="true" />}
        </button>
      </div>

      {panel({
        label,
        kind,
        options: options ?? [],
        labels,
        filter,
        onApply,
        onSort: (d) => setSort(sortKey, d),
      })}
    </th>
  );
}
