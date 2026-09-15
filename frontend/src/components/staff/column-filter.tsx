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

/**
 * Excel-style AutoFilter for the staff console's tables.
 *
 * `SortableTh` gives a column a sort caret and nothing else, so narrowing a register to one person
 * meant typing their name into a free-text box next to the table — fine for a name you remember
 * exactly, useless for picking one of fifty off a roster. This is the header dropdown people already
 * know from a spreadsheet: sort, a searchable checkbox list of the values actually present, OK/Cancel.
 *
 * Two behaviours are deliberately Excel's rather than simpler alternatives:
 *  - **Edits are staged.** Ticking boxes changes nothing until OK; Cancel discards. Filtering a long
 *    list one re-render per click would make the rows jump under the cursor being clicked.
 *  - **The value list respects the OTHER columns' filters** (`optionsFor` below). Once Role is
 *    narrowed to Credit Executive, the Staff dropdown offers only Credit Executives — offering names
 *    that can no longer match would hand the reader empty tables.
 */

/** One filterable column: how to read its display value off a row. */
export interface FilterColumn<T> {
  key: string;
  /** Exactly what the cell renders — the dropdown lists values, so they must match what's on screen. */
  value: (row: T) => string;
}

/** `undefined` for a column means unfiltered; an array is the allow-list of accepted values. */
type Selections = Record<string, string[] | undefined>;

export interface UseColumnFiltersResult<T> {
  /** `rows` with every column's filter applied. */
  filtered: T[];
  /** The allow-list for a column, or `undefined` when it isn't filtered. */
  selectionFor: (key: string) => string[] | undefined;
  /** Distinct values still reachable for a column, given the other columns' filters. */
  optionsFor: (key: string) => string[];
  /** Commit a column's allow-list. Pass `undefined` to clear it. */
  setFilter: (key: string, values: string[] | undefined) => void;
  clearAll: () => void;
  /** How many columns are currently filtered — for the "Clear filters" affordance. */
  activeCount: number;
}

/**
 * Client-side multi-column value filtering. `columns` must be referentially stable across renders
 * (wrap it in `useMemo`) — it carries accessor closures, so a fresh array each render would rebuild
 * every memo here on every keystroke elsewhere on the page.
 */
export function useColumnFilters<T>(
  rows: T[],
  columns: Array<FilterColumn<T>>,
): UseColumnFiltersResult<T> {
  const [selections, setSelections] = React.useState<Selections>({});

  const byKey = React.useMemo(() => {
    const m = new Map<string, FilterColumn<T>>();
    for (const c of columns) m.set(c.key, c);
    return m;
  }, [columns]);

  /** Apply every active filter except one column's — the basis for both `filtered` and `optionsFor`. */
  const applyExcept = React.useCallback(
    (exceptKey: string | null): T[] => {
      const active = Object.entries(selections).filter(
        ([key, values]) => values !== undefined && key !== exceptKey && byKey.has(key),
      ) as Array<[string, string[]]>;
      if (active.length === 0) return rows;
      // Sets, not Array.includes: a roster filtered to one name off a 50-row list is O(n) per row otherwise.
      const allow = active.map(([key, values]) => [byKey.get(key)!, new Set(values)] as const);
      return rows.filter((row) => allow.every(([col, set]) => set.has(col.value(row))));
    },
    [rows, selections, byKey],
  );

  const filtered = React.useMemo(() => applyExcept(null), [applyExcept]);

  const optionsFor = React.useCallback(
    (key: string): string[] => {
      const col = byKey.get(key);
      if (!col) return [];
      const seen = new Set<string>();
      for (const row of applyExcept(key)) seen.add(col.value(row));
      return [...seen].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
    },
    [applyExcept, byKey],
  );

  const selectionFor = React.useCallback((key: string) => selections[key], [selections]);

  const setFilter = React.useCallback((key: string, values: string[] | undefined) => {
    setSelections((prev) => {
      const next = { ...prev };
      if (values === undefined) delete next[key];
      else next[key] = values;
      return next;
    });
  }, []);

  const clearAll = React.useCallback(() => setSelections({}), []);

  const activeCount = Object.values(selections).filter((v) => v !== undefined).length;

  return { filtered, selectionFor, optionsFor, setFilter, clearAll, activeCount };
}

/* ------------------------------------------------------------------------- */

interface PanelProps {
  label: string;
  anchor: DOMRect;
  options: string[];
  selected: string[] | undefined;
  onApply: (next: string[] | undefined) => void;
  onSort: (dir: SortDir) => void;
  onClose: () => void;
}

const PANEL_WIDTH = 248;

/** The dropdown body. Rendered into a portal — see `FilterableTh`. */
function FilterPanel({ label, anchor, options, selected, onApply, onSort, onClose }: PanelProps) {
  const ref = React.useRef<HTMLDivElement>(null);
  const [search, setSearch] = React.useState("");
  // Staged selection: `selected === undefined` (unfiltered) opens with everything ticked, which is
  // what the reader sees in the table and therefore what they expect the boxes to say.
  const [draft, setDraft] = React.useState<Set<string>>(
    () => new Set(selected ?? options),
  );

  const visible = React.useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return options;
    return options.filter((o) => o.toLowerCase().includes(needle));
  }, [options, search]);

  const allVisibleChecked = visible.length > 0 && visible.every((o) => draft.has(o));

  const toggleValue = (value: string) =>
    setDraft((prev) => {
      const next = new Set(prev);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });

  /** Select All applies to what the search has narrowed to — Excel's behaviour, and the only one
   *  that lets someone search "cred", tick all, and keep their earlier picks. */
  const toggleAllVisible = () =>
    setDraft((prev) => {
      const next = new Set(prev);
      if (allVisibleChecked) for (const o of visible) next.delete(o);
      else for (const o of visible) next.add(o);
      return next;
    });

  const commit = () => {
    // Everything ticked is the same as no filter — store it as such so the column stops showing as
    // filtered and `optionsFor` on other columns stops narrowing against a no-op.
    onApply(draft.size === options.length ? undefined : [...draft]);
    onClose();
  };

  React.useEffect(() => {
    const inside = (target: EventTarget | null) =>
      !!ref.current && target instanceof Node && ref.current.contains(target);

    const onDown = (e: MouseEvent) => {
      if (!inside(e.target)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    // The panel is positioned off a one-time snapshot of the header's rect, so anything that moves
    // the header invalidates it — close rather than chase. Scrolling the value list is NOT that:
    // it fires a scroll event whose target is the list itself, and closing on it would dismiss the
    // panel the moment anyone scrolled to the name they came for.
    const onScroll = (e: Event) => {
      if (!inside(e.target)) onClose();
    };

    // Capture, so a click dismisses the panel before any row handler underneath it runs.
    document.addEventListener("mousedown", onDown, true);
    document.addEventListener("keydown", onKey);
    window.addEventListener("scroll", onScroll, true);
    window.addEventListener("resize", onClose);
    return () => {
      document.removeEventListener("mousedown", onDown, true);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("resize", onClose);
    };
  }, [onClose]);

  // Clamp into the viewport: these headers sit inside a horizontal scroller and the last columns
  // are hard against the right edge, where an unclamped panel would hang off-screen.
  const left = Math.max(
    8,
    Math.min(anchor.left, (typeof window !== "undefined" ? window.innerWidth : PANEL_WIDTH) - PANEL_WIDTH - 8),
  );

  // Vertical placement needs the rendered height, so it is measured rather than estimated: a table
  // header sitting low on screen (charts and stat tiles above it, which is the normal layout here)
  // would otherwise push OK/Cancel below the fold, leaving no way to apply the filter.
  const [top, setTop] = React.useState<number | null>(null);
  React.useLayoutEffect(() => {
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
  }, [anchor, options.length]);

  return (
    <div
      ref={ref}
      role="dialog"
      aria-label={`Filter and sort ${label}`}
      style={{
        position: "fixed",
        top: top ?? anchor.bottom + 4,
        left,
        width: PANEL_WIDTH,
        zIndex: 60,
        // Final guard for a viewport too short for either placement — the list flexes away
        // (`min-h-0` below) so the action row survives.
        maxHeight: "calc(100vh - 16px)",
        visibility: top == null ? "hidden" : "visible",
      }}
      className="flex flex-col overflow-hidden rounded-lg border border-line bg-white text-ink shadow-xl"
    >
      <div className="flex shrink-0 flex-col py-1 text-xs">
        <button type="button" onClick={() => { onSort("asc"); onClose(); }} className="flex items-center gap-2 px-3 py-1.5 text-left hover:bg-grey-100">
          <ArrowDownAZ size={14} className="text-muted" /> Sort A to Z
        </button>
        <button type="button" onClick={() => { onSort("desc"); onClose(); }} className="flex items-center gap-2 px-3 py-1.5 text-left hover:bg-grey-100">
          <ArrowUpAZ size={14} className="text-muted" /> Sort Z to A
        </button>
      </div>

      <div className="shrink-0 border-t border-line py-1">
        <button
          type="button"
          disabled={selected === undefined}
          onClick={() => { onApply(undefined); onClose(); }}
          className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-grey-100 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
        >
          <FilterX size={14} className="text-muted" /> Clear filter from &ldquo;{label}&rdquo;
        </button>
      </div>

      <div className="flex min-h-0 flex-1 flex-col border-t border-line p-2">
        <div className="relative mb-2 shrink-0">
          <Search size={13} className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted" />
          <input
            autoFocus
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
                    checked={draft.has(o)}
                    onChange={() => toggleValue(o)}
                    className="h-3.5 w-3.5 accent-navy"
                  />
                  <span className="truncate" title={o}>{o || "(blank)"}</span>
                </label>
              ))}
            </>
          )}
        </div>
      </div>

      <div className="flex shrink-0 justify-end gap-2 border-t border-line px-2 py-2">
        <button type="button" onClick={onClose} className="rounded border border-line px-3 py-1 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink">
          Cancel
        </button>
        <button
          type="button"
          onClick={commit}
          disabled={draft.size === 0}
          className="rounded bg-navy px-3 py-1 text-xs font-semibold text-white hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
        >
          OK
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
  options: string[];
  selected: string[] | undefined;
  onApply: (next: string[] | undefined) => void;
  className?: string;
}

/**
 * A `<th>` that sorts on the label (like `SortableTh`) and opens the AutoFilter on the caret button.
 *
 * The panel is portalled to `document.body` rather than positioned inside the cell: these tables live
 * in `.staff-table-scroll` (`overflow-x: auto`, which computes `overflow-y` to `auto` as well), so an
 * absolutely-positioned dropdown would be clipped at the header's bottom edge instead of overlaying
 * the rows.
 */
export function FilterableTh({
  label,
  sortKey,
  active,
  dir,
  onToggle,
  setSort,
  options,
  selected,
  onApply,
  className,
}: FilterableThProps) {
  const isActive = sortKey === active;
  const isFiltered = selected !== undefined;
  const btnRef = React.useRef<HTMLButtonElement>(null);
  const [anchor, setAnchor] = React.useState<DOMRect | null>(null);

  const ariaSort: React.AriaAttributes["aria-sort"] = isActive
    ? dir === "asc" ? "ascending" : "descending"
    : "none";

  const open = () => setAnchor(btnRef.current?.getBoundingClientRect() ?? null);

  return (
    <th className={className} aria-sort={ariaSort}>
      <div className="flex items-center gap-1">
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

        <button
          ref={btnRef}
          type="button"
          onClick={() => (anchor ? setAnchor(null) : open())}
          aria-haspopup="dialog"
          aria-expanded={anchor != null}
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

      {anchor
        && createPortal(
          <FilterPanel
            label={label}
            anchor={anchor}
            options={options}
            selected={selected}
            onApply={onApply}
            onSort={(d) => setSort(sortKey, d)}
            onClose={() => setAnchor(null)}
          />,
          document.body,
        )}
    </th>
  );
}
