"use client";

import * as React from "react";
import { ChevronDown, ListFilter, Plus, X } from "lucide-react";
import { useFilterAnchor } from "./column-filter";
import { describeFilter } from "@/lib/staff/column-filter-params";
import type { ColumnFilter } from "@/lib/staff/column-filter-match";
import type { ThBag } from "@/lib/staff/register-th";

/**
 * The chip strip above a register: one chip per active filter, `⊕ Add filter`, the result count and
 * `✕ Clear filters`. It is a presentation layer over the same `ThBag` the header carets drive, so a
 * filter set in either place shows in both.
 *
 * Two regions on purpose: only the chips scroll. Anything inside the scroller would drift out of
 * reach as chips accumulate, so the add / count / clear controls are pinned beside it.
 */
export function FilterBar({
  th,
  total,
  onClear,
}: {
  th: ThBag;
  total: number;
  /**
   * Pages pass `() => rq.setFilters({})`, NOT `rq.clearAll`: `clearAll` also wipes the search box, and
   * a control labelled "Clear filters" must not silently discard what someone typed there. For the
   * same reason the bar counts filters from `th.filters` and never reads `rq.activeCount`, which
   * counts the search term too.
   */
  onClear: () => void;
}) {
  const { isOpen, open, close, panel } = useFilterAnchor();
  const [openKey, setOpenKey] = React.useState<string | null>(null);
  const [menuOpen, setMenuOpen] = React.useState(false);

  // Most-recently-applied first. A `ColumnFilterSet` is a Record and `setFilter` spreads the previous
  // object, so key order cannot say "most recent"; and `serialiseFilters` sorts keys on purpose so
  // equal sets share one cache entry, so this order must never reach the URL. It is therefore
  // ephemeral: a reload, a restored view or a shared link has no application order to recover, and
  // those chips simply fall in behind, in column order.
  const [order, setOrder] = React.useState<string[]>([]);

  const scrollerRef = React.useRef<HTMLDivElement>(null);
  const addRef = React.useRef<HTMLButtonElement>(null);
  const menuRef = React.useRef<HTMLDivElement>(null);
  const [focusTick, setFocusTick] = React.useState(0);

  const active = th.keys.filter((k) => th.filters[k] !== undefined);
  const chips = [...order.filter((k) => active.includes(k)), ...active.filter((k) => !order.includes(k))];
  const idle = th.keys.filter((k) => th.filters[k] === undefined);

  // New chips land at the left, but a browser keeps the scroll offset across a DOM insert, so a row
  // already scrolled right would hide the chip just added and defeat newest-first.
  React.useLayoutEffect(() => {
    if (scrollerRef.current) scrollerRef.current.scrollLeft = 0;
  }, [order]);

  // A removed chip takes its own button with it; without this, focus falls to <body>.
  React.useEffect(() => {
    if (focusTick > 0) addRef.current?.focus();
  }, [focusTick]);

  // Same dismissal as `FilterPanel` (its effect also closes on scroll/resize, which a menu hanging
  // off a pinned chip does not need, so this is a small mirror rather than a shared hook).
  React.useEffect(() => {
    if (!menuOpen) return;
    // Capture, so a click dismisses the menu before any handler underneath it runs. The wrapper
    // holds the Add filter button too, so clicking it toggles instead of close-then-reopen.
    const onDown = (e: MouseEvent) => {
      if (!(e.target instanceof Node && menuRef.current?.contains(e.target))) setMenuOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      // Stop here, or a bar inside a Dialog/Drawer closes the whole dialog along with the menu.
      e.stopPropagation();
      setMenuOpen(false);
      addRef.current?.focus();
    };
    document.addEventListener("mousedown", onDown, true);
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("mousedown", onDown, true);
      document.removeEventListener("keydown", onKey, true);
    };
  }, [menuOpen]);

  if (th.keys.length === 0) return null;

  const apply = (key: string, next: ColumnFilter | undefined) => {
    th.setFilter(key, next);
    setOrder((prev) => (next ? [key, ...prev.filter((k) => k !== key)] : prev.filter((k) => k !== key)));
  };
  const remove = (key: string) => {
    apply(key, undefined);
    setFocusTick((n) => n + 1);
  };
  const openPanel = (key: string, anchorEl: HTMLElement | null) => {
    setOpenKey(key);
    open(anchorEl, () => th.onOpenFacet?.(key));
  };

  // Local utilities, not `.cal-preset`: globals.css makes its :hover and .on one declaration, so an
  // unselected chip would look selected on hover. Gold matches the filtered caret in column-filter.
  const chipBase = "inline-flex shrink-0 items-center rounded-full border text-xs";

  return (
    // A bordered, tinted strip rather than controls floating on the page background: the row has to
    // read as one filter control at a glance, next to a toolbar and a table header that are both
    // visually busier than it.
    <div
      className="mb-3 flex items-center gap-2 rounded-lg border border-line bg-white/70 px-2.5 py-1.5"
      data-filter-bar
    >
      <span className="flex shrink-0 items-center gap-1.5 pr-1 text-xs font-semibold text-muted">
        <ListFilter size={13} aria-hidden="true" />
        {/* Names the row. Without it the chips read as stray buttons between the search box and
            the table, which is exactly how this looked before. */}
        <span className="hidden sm:inline">Filters</span>
      </span>
      <div ref={scrollerRef} className="pill-tabs min-w-0 flex-1">
        <div className="flex flex-nowrap items-center gap-2 py-0.5">
          {chips.map((key) => {
            const header = th.headerFor(key);
            const f = th.filters[key];
            const text = f ? describeFilter(header, f, (v) => th.labelsFor(key).get(v) ?? v) : header;
            // `describeFilter` writes a sentence ("Status in Active"); a chip shows the column name
            // and the value either side of a divider, so the leading header goes. The "in" of a
            // checklist goes too — the divider already says it — while the operator of a text, number
            // or date filter stays, because "contains", "≥" and "to" are the whole meaning there.
            const value = (text.startsWith(header) ? text.slice(header.length).trim() : text).replace(/^in /, "");
            const expanded = isOpen && openKey === key;
            return (
              <span
                key={key}
                className={`${chipBase} border-navy bg-navy text-white shadow-sm ${
                  expanded ? "ring-2 ring-gold ring-offset-1" : ""
                }`}
              >
                <button
                  type="button"
                  aria-haspopup="dialog"
                  aria-expanded={expanded}
                  title={text}
                  onClick={(e) => (expanded ? close() : openPanel(key, e.currentTarget))}
                  onKeyDown={(e) => {
                    if (e.key === "Backspace" || e.key === "Delete") {
                      e.preventDefault();
                      remove(key);
                    }
                  }}
                  className="flex min-w-0 items-center gap-1.5 rounded-l-full py-1 pl-2.5 pr-1.5 hover:bg-navy-700"
                >
                  {/* Column name muted, the VALUE in gold with a divider between them — the thing a
                      reader scans for is what the filter is set TO, not which column it is on. */}
                  <span className="whitespace-nowrap text-white/70">{header}</span>
                  <span aria-hidden="true" className="h-3 w-px bg-white/25" />
                  <span className="max-w-[16rem] truncate font-semibold text-gold">{value}</span>
                  <ChevronDown size={12} aria-hidden="true" className="text-white/70" />
                </button>
                <button
                  type="button"
                  aria-label={`Remove ${header} filter`}
                  onClick={() => remove(key)}
                  className="rounded-r-full py-1 pl-1 pr-2 text-white/60 hover:text-white"
                >
                  <X size={12} aria-hidden="true" />
                </button>
              </span>
            );
          })}
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-3">
        {idle.length > 0 && (
          <div ref={menuRef} className="relative">
            <button
              ref={addRef}
              type="button"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((v) => !v)}
              className={`${chipBase} gap-1 border-dashed border-navy/35 bg-white px-2.5 py-1 font-semibold text-navy hover:border-navy hover:bg-navy-tint`}
            >
              <Plus size={12} aria-hidden="true" /> Add filter
            </button>
            {menuOpen && (
              <ul role="menu" className="absolute right-0 top-full z-30 mt-1 max-h-64 min-w-[11rem] overflow-auto rounded-lg border border-line bg-white py-1 text-xs shadow-xl">
                {idle.map((key) => (
                  <li key={key} role="none">
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => {
                        setMenuOpen(false);
                        openPanel(key, addRef.current);
                      }}
                      className="block w-full px-3 py-1.5 text-left hover:bg-grey-100"
                    >
                      {th.headerFor(key)}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        {active.length > 0 && (
          <>
            {/*
              * The count appears ONLY when the filters have emptied the register.
              *
              * `PaginationBar` already says "Showing 1–25 of N" a few pixels below, and on screen the
              * two read as one number printed twice — the reference this bar is modelled on puts its
              * count with the pagination, not in the filter strip. But `PaginationBar` returns `null`
              * at `total === 0` (`pipeline/pagination.tsx`), so the one moment a reader most needs
              * telling *why* the table is blank is the one moment nothing says anything. This fills
              * exactly that hole and stays quiet otherwise.
              */}
            {total === 0 && (
              <span className="whitespace-nowrap text-xs text-muted">No rows match</span>
            )}
            <button
              type="button"
              onClick={() => {
                setOrder([]);
                onClear();
              }}
              className="inline-flex items-center gap-1 whitespace-nowrap rounded border border-line px-2 py-1 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink"
            >
              <X size={12} aria-hidden="true" /> Clear filters
            </button>
          </>
        )}
      </div>

      {openKey &&
        panel({
          label: th.headerFor(openKey),
          kind: th.kindFor(openKey),
          options: th.optionsFor(openKey),
          labels: th.labelsFor(openKey),
          filter: th.filters[openKey],
          onApply: (next) => apply(openKey, next),
        })}
    </div>
  );
}
