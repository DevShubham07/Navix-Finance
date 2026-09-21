"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import {
  Search,
  Loader2,
  X,
  CornerDownLeft,
  Contact,
  Workflow,
  Landmark,
  HandCoins,
  Phone,
  Users,
  Ban,
} from "lucide-react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useFocusTrap } from "@/hooks/use-focus-trap";
import { useGlobalSearchHotkey } from "@/hooks/use-global-search-hotkey";
import { formatApiError } from "@/lib/api/errors";
import {
  searchApi,
  paiseToINR,
  type FeatureFlags,
  type SearchItem,
  type SearchKind,
} from "@/lib/api/applications";
import type { StaffRole } from "@/lib/auth/rbac";
import {
  buildFeatureIndex,
  clearRecent,
  interpretQuery,
  isSearchable,
  matchFeatures,
  pushRecent,
  readRecent,
  QUERY_KIND_LABEL,
  type FeatureHit,
} from "@/lib/staff/global-search";
import { cn } from "@/lib/utils";

/**
 * The staff console's global search — one header trigger plus a Cmd/Ctrl+K palette that jumps to a
 * page, a customer, an application, a loan, a collections case, a lead, a staff user or a blocklist
 * entry.
 *
 * <p>Two halves, deliberately: **pages** resolve locally from the sidebar's own NAV array through
 * the sidebar's own `navVisible` gate (instant, and incapable of offering a page the role cannot
 * open), while **records** come from `GET /api/staff/search`, which runs each group through the same
 * scoped service the corresponding list page uses. A hit in this palette is therefore always a row
 * the caller could already have reached by hand — search widens the path to the data, never the
 * data itself.
 *
 * <p>Not mounted for DSA (the shell gates on the role; the endpoint rejects it too).
 */

const KIND_ICON: Record<SearchKind | "feature", typeof Search> = {
  feature: Search,
  customer: Contact,
  application: Workflow,
  loan: Landmark,
  collections: HandCoins,
  lead: Phone,
  staff: Users,
  blocklist: Ban,
};

/** Status → badge tone, reusing the register's colour language so a chip means the same thing here. */
function badgeTone(badge: string): BadgeVariant {
  const b = badge.toUpperCase();
  if (b.includes("OVERDUE") || b.includes("DEFAULT") || b.includes("REJECT") || b.includes("WRITTEN")) return "error";
  if (b.includes("ACTIVE") || b.includes("CLOSED") || b.includes("REPAID") || b.includes("DISBURSED")) return "success";
  if (b.includes("PENDING") || b.includes("REVIEW") || b.includes("SANCTION")) return "warning";
  return "neutral";
}

/** The list page each group's "View all" link opens, with the query prefilled. */
const VIEW_ALL: Record<SearchKind, string> = {
  customer: "/staff/customers",
  application: "/staff/applications",
  loan: "/staff/loans",
  collections: "/staff/collections",
  lead: "/staff/leads",
  staff: "/staff/admin/staff",
  blocklist: "/staff/admin/blocklist",
};

/** A flat, keyboard-navigable row: either a local page hit or a server record hit. */
type Row =
  | { type: "feature"; hit: FeatureHit }
  | { type: "item"; item: SearchItem };

export function GlobalSearch({
  role,
  staffId,
  flags,
}: {
  role: StaffRole;
  staffId: string;
  flags?: FeatureFlags;
}) {
  const [open, setOpen] = React.useState(false);
  const onOpen = React.useCallback(() => setOpen(true), []);
  const { isMac } = useGlobalSearchHotkey(onOpen, !open);

  return (
    <>
      <button
        type="button"
        onClick={onOpen}
        aria-label="Search"
        title={`Search (${isMac ? "⌘" : "Ctrl "}K)`}
        className={cn(
          // Phone: an icon button sized like the notification bell beside it, with a padded hit area.
          "grid h-9 w-9 place-items-center rounded-full text-muted transition-colors hover:bg-grey-100 hover:text-ink",
          // Tablet up: a search field the eye reads as one, with the shortcut spelled out.
          "sm:flex sm:h-9 sm:w-56 sm:items-center sm:justify-start sm:gap-2 sm:rounded-full sm:border sm:border-line sm:bg-grey-50 sm:px-3 sm:text-sm sm:hover:bg-white lg:w-64",
        )}
      >
        <Search size={16} className="flex-shrink-0" />
        <span className="hidden flex-1 text-left sm:inline">Search…</span>
        <kbd className="hidden rounded border border-line bg-white px-1.5 py-0.5 font-sans text-[10px] text-muted sm:inline">
          {isMac ? "⌘" : "Ctrl "}K
        </kbd>
      </button>

      {open && (
        <SearchPalette
          role={role}
          staffId={staffId}
          flags={flags}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

function SearchPalette({
  role,
  staffId,
  flags,
  onClose,
}: {
  role: StaffRole;
  staffId: string;
  flags?: FeatureFlags;
  onClose: () => void;
}) {
  const router = useRouter();
  const [q, setQ] = React.useState("");
  const [active, setActive] = React.useState(0);
  const [recent, setRecent] = React.useState<string[]>([]);
  const panelRef = useFocusTrap<HTMLDivElement>(true);
  const listRef = React.useRef<HTMLDivElement>(null);
  const listId = React.useId();

  React.useEffect(() => setRecent(readRecent(staffId)), [staffId]);

  // Body-scroll lock while the sheet is up (the mobile sheet is full-height; a scrolling page
  // underneath it is how you lose your place in a queue).
  React.useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  // Escape closes ONLY the palette. Capture phase + stopPropagation so an underlying Dialog/Drawer
  // (both listen on document in the bubble phase) does not also close behind it.
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.stopPropagation();
      onClose();
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
  }, [onClose]);

  const featureIndex = React.useMemo(() => buildFeatureIndex(role, flags), [role, flags]);
  const featureHits = React.useMemo(() => matchFeatures(featureIndex, q, 5), [featureIndex, q]);

  const debounced = useDebouncedValue(q.trim(), 250);
  const searchable = isSearchable(debounced);
  const results = useQuery({
    queryKey: ["global-search", debounced],
    queryFn: () => searchApi.global(debounced),
    enabled: searchable,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    retry: false,
  });

  // While a new query is in flight `keepPreviousData` deliberately leaves the previous groups on
  // screen (dimmed by the spinner rather than collapsing the list), so this is the last *settled*
  // result, not necessarily one matching `debounced`. The query key guarantees it catches up.
  const groups = React.useMemo(() => results.data?.groups ?? [], [results.data]);
  const interpreted = interpretQuery(q);

  const rows = React.useMemo<Row[]>(
    () => [
      ...featureHits.map((hit) => ({ type: "feature" as const, hit })),
      ...groups.flatMap((g) => g.items.map((item) => ({ type: "item" as const, item }))),
    ],
    [featureHits, groups],
  );

  React.useEffect(() => setActive(0), [q]);

  // Keep the highlighted row on screen as the arrows walk past the fold.
  React.useEffect(() => {
    listRef.current
      ?.querySelector(`[data-row="${active}"]`)
      ?.scrollIntoView({ block: "nearest" });
  }, [active, rows.length]);

  const go = React.useCallback(
    (href: string) => {
      if (q.trim()) setRecent(pushRecent(staffId, q.trim()));
      onClose();
      router.push(href);
    },
    [q, staffId, onClose, router],
  );

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((i) => (rows.length ? (i + 1) % rows.length : 0));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => (rows.length ? (i - 1 + rows.length) % rows.length : 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      const row = rows[active] ?? rows[0];
      if (row) go(row.type === "feature" ? row.hit.href : row.item.href);
    }
  };

  const busy = results.isFetching;
  const showEmptyState = !q.trim();
  const noResults = searchable && !busy && rows.length === 0 && !results.isError;

  return createPortal(
    <div
      className="modal-overlay modal-overlay--top show"
      onClick={onClose}
      /* The palette owns the whole viewport on a phone; a centred 460px card would waste it. */
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="Search the console"
        onClick={(e) => e.stopPropagation()}
        // `.modal` is centre-aligned for the confirm dialogs it was built for; an inline style is
        // how the shared `Dialog` primitive overrides that too (a utility class only ties on
        // specificity and loses to stylesheet order).
        style={{ textAlign: "left" }}
        className="modal flex w-full max-w-2xl flex-col overflow-hidden !p-0 max-sm:h-[100dvh] max-sm:max-h-none max-sm:!rounded-none"
      >
        {/* ---- input row ---- */}
        <div className="flex items-center gap-3 border-b border-line px-4 py-3">
          {busy ? (
            <Loader2 size={18} className="flex-shrink-0 animate-spin text-muted" />
          ) : (
            <Search size={18} className="flex-shrink-0 text-muted" />
          )}
          <input
            autoFocus
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={onKeyDown}
            role="combobox"
            aria-expanded
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={rows.length ? `${listId}-row-${active}` : undefined}
            autoComplete="off"
            enterKeyHint="search"
            inputMode={/^\d+$/.test(q.trim()) ? "numeric" : "text"}
            placeholder="Search customers, loans, leads or pages…"
            // The global a11y focus ring is suppressed here the same way `.field input:focus` does
            // it: the palette itself is the focus affordance, and a second ring inside it reads as
            // a form field that has gone wrong.
            className="min-w-0 flex-1 bg-transparent text-base text-ink outline-none placeholder:text-muted focus-visible:outline-none"
          />
          {q && (
            <button
              type="button"
              onClick={() => setQ("")}
              aria-label="Clear"
              className="grid h-7 w-7 flex-shrink-0 place-items-center rounded-full text-muted hover:bg-grey-100 hover:text-ink"
            >
              <X size={15} />
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            className="hidden flex-shrink-0 rounded border border-line px-1.5 py-0.5 text-[10px] text-muted hover:bg-grey-100 sm:block"
          >
            esc
          </button>
        </div>

        {q.trim() && interpreted.kind !== "text" && (
          <p className="m-0 border-b border-line bg-grey-50 px-4 py-1.5 text-[11px] text-muted">
            {QUERY_KIND_LABEL[interpreted.kind]} · <span className="font-mono">{interpreted.value}</span>
          </p>
        )}

        {/* ---- results ---- */}
        <div
          ref={listRef}
          id={listId}
          role="listbox"
          aria-label="Search results"
          className="min-h-0 flex-1 overflow-y-auto sm:max-h-[min(60vh,32rem)]"
        >
          {showEmptyState ? (
            <EmptyState
              recent={recent}
              features={featureIndex.slice(0, 6)}
              onPick={setQ}
              onGo={go}
              onClearRecent={() => {
                clearRecent(staffId);
                setRecent([]);
              }}
            />
          ) : (
            <>
              {featureHits.length > 0 && (
                <GroupHeading label="Pages" />
              )}
              {featureHits.map((hit, i) => (
                <ResultRow
                  key={hit.id}
                  index={i}
                  listId={listId}
                  activeIndex={active}
                  onHover={setActive}
                  onSelect={() => go(hit.href)}
                  Icon={hit.Icon}
                  title={hit.title}
                  subtitle={hit.subtitle}
                  query={q}
                />
              ))}

              {groups.map((group) => {
                const offset =
                  featureHits.length +
                  groups.slice(0, groups.indexOf(group)).reduce((n, g) => n + g.items.length, 0);
                return (
                  <React.Fragment key={group.kind}>
                    <GroupHeading
                      label={group.label}
                      viewAll={
                        group.more
                          ? `${VIEW_ALL[group.kind]}?q=${encodeURIComponent(debounced)}`
                          : undefined
                      }
                      onViewAll={go}
                    />
                    {group.items.map((item, i) => (
                      <ResultRow
                        key={`${item.kind}-${item.id}`}
                        index={offset + i}
                        listId={listId}
                        activeIndex={active}
                        onHover={setActive}
                        onSelect={() => go(item.href)}
                        Icon={KIND_ICON[item.kind]}
                        title={item.title}
                        subtitle={item.subtitle}
                        badge={item.badge}
                        amountPaise={
                          typeof item.meta?.amountPaise === "number"
                            ? item.meta.amountPaise
                            : typeof item.meta?.outstandingPaise === "number"
                              ? item.meta.outstandingPaise
                              : undefined
                        }
                        query={q}
                      />
                    ))}
                  </React.Fragment>
                );
              })}

              {results.isError && (
                <p className="m-0 px-4 py-6 text-center text-sm text-error-700">
                  {formatApiError(results.error, "Search is unavailable right now.")}
                </p>
              )}

              {noResults && (
                <div className="px-4 py-8 text-center">
                  <p className="m-0 text-sm text-ink">
                    No matches for <span className="font-semibold">“{q.trim()}”</span>
                  </p>
                  <p className="m-0 mt-1 text-xs text-muted">
                    {QUERY_KIND_LABEL[interpreted.kind]} — try a name, mobile, PAN or #id.
                  </p>
                </div>
              )}

              {!searchable && featureHits.length === 0 && (
                <p className="m-0 px-4 py-8 text-center text-xs text-muted">Keep typing to search records…</p>
              )}
            </>
          )}
        </div>

        {/* ---- footer (desktop only; there is no keyboard to hint at on a phone) ---- */}
        <div className="hidden items-center gap-4 border-t border-line bg-grey-50 px-4 py-2 text-[11px] text-muted sm:flex">
          <span className="flex items-center gap-1">
            <kbd className="rounded border border-line bg-white px-1">↑</kbd>
            <kbd className="rounded border border-line bg-white px-1">↓</kbd> navigate
          </span>
          <span className="flex items-center gap-1">
            <kbd className="rounded border border-line bg-white px-1">
              <CornerDownLeft size={9} className="inline" />
            </kbd>{" "}
            open
          </span>
          <span className="flex items-center gap-1">
            <kbd className="rounded border border-line bg-white px-1">esc</kbd> close
          </span>
        </div>
      </div>
    </div>,
    document.body,
  );
}

function GroupHeading({
  label,
  viewAll,
  onViewAll,
}: {
  label: string;
  viewAll?: string;
  onViewAll?: (href: string) => void;
}) {
  return (
    <div className="sticky top-0 z-10 flex items-center justify-between bg-white/95 px-4 pb-1 pt-3 text-[11px] font-semibold uppercase tracking-wide text-muted backdrop-blur">
      <span>{label}</span>
      {viewAll && (
        <button
          type="button"
          onClick={() => onViewAll?.(viewAll)}
          className="font-medium normal-case tracking-normal text-navy hover:underline"
        >
          View all →
        </button>
      )}
    </div>
  );
}

/** Highlight the matched run as React nodes — the text is borrower data and never becomes markup. */
function Highlight({ text, query }: { text: string; query: string }) {
  const q = query.trim();
  if (!q) return <>{text}</>;
  const at = text.toLowerCase().indexOf(q.toLowerCase());
  if (at < 0) return <>{text}</>;
  return (
    <>
      {text.slice(0, at)}
      <mark className="bg-gold-50 text-navy">{text.slice(at, at + q.length)}</mark>
      {text.slice(at + q.length)}
    </>
  );
}

function ResultRow({
  index,
  listId,
  activeIndex,
  onHover,
  onSelect,
  Icon,
  title,
  subtitle,
  badge,
  amountPaise,
  query,
}: {
  index: number;
  listId: string;
  activeIndex: number;
  onHover: (i: number) => void;
  onSelect: () => void;
  Icon: typeof Search;
  title: string;
  subtitle?: string | null;
  badge?: string | null;
  amountPaise?: number;
  query: string;
}) {
  const isActive = index === activeIndex;
  return (
    <div
      id={`${listId}-row-${index}`}
      data-row={index}
      role="option"
      aria-selected={isActive}
      tabIndex={-1}
      onMouseMove={() => onHover(index)}
      // mousedown, not click: the input must not lose focus before the navigation runs.
      onMouseDown={(e) => {
        e.preventDefault();
        onSelect();
      }}
      className={cn(
        // A constant-width left border (transparent when idle) keeps rows from shifting 2px as the
        // selection walks down the list.
        "flex cursor-pointer items-center gap-3 border-l-2 px-4 py-2.5 max-sm:py-3",
        isActive ? "border-navy bg-navy-tint" : "border-transparent hover:bg-grey-50",
      )}
    >
      <Icon size={16} className="flex-shrink-0 text-muted" />
      {/* `m-0`: globals.css gives every <p> a 1em bottom margin, which doubles the row height. */}
      <div className="min-w-0 flex-1">
        <p className="m-0 truncate text-sm font-medium text-ink">
          <Highlight text={title} query={query} />
        </p>
        {subtitle && <p className="m-0 truncate text-xs text-muted">{subtitle}</p>}
      </div>
      {amountPaise != null && (
        <span className="flex-shrink-0 font-mono text-xs text-muted">{paiseToINR(amountPaise)}</span>
      )}
      {badge && (
        <Badge variant={badgeTone(badge)} size="sm" className="flex-shrink-0 max-sm:hidden">
          {badge.replace(/_/g, " ")}
        </Badge>
      )}
    </div>
  );
}

function EmptyState({
  recent,
  features,
  onPick,
  onGo,
  onClearRecent,
}: {
  recent: string[];
  features: FeatureHit[];
  onPick: (q: string) => void;
  onGo: (href: string) => void;
  onClearRecent: () => void;
}) {
  return (
    <div className="px-4 py-3">
      {recent.length > 0 && (
        <div className="mb-4">
          <div className="mb-2 flex items-center justify-between text-[11px] font-semibold uppercase tracking-wide text-muted">
            <span>Recent</span>
            <button type="button" onClick={onClearRecent} className="font-medium normal-case tracking-normal hover:underline">
              Clear
            </button>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {recent.map((r) => (
              <button
                key={r}
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  onPick(r);
                }}
                className="max-w-full truncate rounded-full border border-line bg-grey-50 px-2.5 py-1 text-xs text-ink hover:bg-white"
              >
                {r}
              </button>
            ))}
          </div>
        </div>
      )}

      <p className="m-0 mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Jump to</p>
      {features.map((hit) => (
        <div
          key={hit.id}
          role="option"
          aria-selected={false}
          tabIndex={-1}
          onMouseDown={(e) => {
            e.preventDefault();
            onGo(hit.href);
          }}
          className="flex cursor-pointer items-center gap-3 rounded px-1 py-2 hover:bg-grey-50"
        >
          <hit.Icon size={16} className="flex-shrink-0 text-muted" />
          <span className="truncate text-sm text-ink">{hit.title}</span>
          <span className="ml-auto flex-shrink-0 text-[11px] text-muted">{hit.subtitle}</span>
        </div>
      ))}

      <p className="m-0 mt-3 border-t border-line pt-3 text-[11px] text-muted">
        Type a name, mobile, PAN or <span className="font-mono">#id</span>.
      </p>
    </div>
  );
}
