"use client";

import * as React from "react";
import { Crown, Medal, Search, Trophy } from "lucide-react";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { StarRating } from "@/components/ui/star-rating";
import type { DashLeaderboard, DashLeaderboardRow } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { MEDAL, MEDAL_GLOW, NAVY, PODIUM_BG, PCT_TONE_COLOR, TONE_SOLID, TONE_TEXT, pctTone } from "./colors";
import { fmtScore } from "./rating-factors";

function FactorPopover({ row }: { row: DashLeaderboardRow }) {
  return (
    <div
      role="tooltip"
      className="pointer-events-none absolute left-1/2 top-full z-30 mt-2 w-64 -translate-x-1/2 rounded-xl border border-line bg-paper p-3 text-left text-[11px] text-ink shadow-md"
    >
      <p className="m-0 mb-1.5 font-semibold text-ink">
        #{row.rank} {row.name} · {fmtScore(row.score)} / 10
      </p>
      {row.factors && row.factors.length > 0 ? (
        <ul className="m-0 list-none space-y-1 p-0">
          {row.factors.map((f) => (
            <li key={f.key} className="flex items-center justify-between gap-3">
              <span className="text-slate">
                {f.label} <span className="text-muted">(weight {f.weight})</span>
              </span>
              <span className="tabular-nums font-semibold">
                {f.display} · {f.stars == null ? "—" : `${f.stars}★`}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="m-0 text-slate">Factor detail is shown for your own row only.</p>
      )}
      <p className="m-0 mt-2 border-t border-line pt-1.5 text-[10px] text-slate">
        Score = 10 × weighted average of the factors that can be measured. Click to see the files.
      </p>
    </div>
  );
}

function Person({
  row,
  children,
  onOpen,
  className,
}: {
  row: DashLeaderboardRow;
  children: React.ReactNode;
  onOpen?: (row: DashLeaderboardRow) => void;
  className?: string;
}) {
  const [hover, setHover] = React.useState(false);
  return (
    <div
      className="relative"
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onFocus={() => setHover(true)}
      onBlur={() => setHover(false)}
    >
      <button
        type="button"
        onClick={() => onOpen?.(row)}
        aria-label={`${row.name}, rank ${row.rank}, score ${fmtScore(row.score)} out of 10`}
        className={cn("w-full text-left transition hover:-translate-y-0.5", className)}
      >
        {children}
      </button>
      {hover && <FactorPopover row={row} />}
    </div>
  );
}

function PodiumSpot({
  row,
  place,
  onOpen,
}: {
  row: DashLeaderboardRow | undefined;
  place: 1 | 2 | 3;
  onOpen?: (r: DashLeaderboardRow) => void;
}) {
  if (!row) return <div />;
  const first = place === 1;
  return (
    <Person row={row} onOpen={onOpen} className={cn("flex flex-col items-center", first ? "-mt-3" : "mt-2")}>
      <span className="relative">
        {first && (
          <Crown size={22} aria-hidden className="absolute -top-5 left-1/2 -translate-x-1/2" style={{ color: MEDAL[1] }} />
        )}
        <span
          className={cn(
            "flex items-center justify-center rounded-full font-semibold text-white",
            first ? "h-16 w-16 text-xl" : "h-12 w-12 text-base",
          )}
          style={{ background: NAVY, boxShadow: first ? MEDAL_GLOW : undefined }}
        >
          {row.name.charAt(0).toUpperCase()}
        </span>
        <Medal
          size={first ? 20 : 16}
          aria-hidden
          className="absolute -bottom-1 -right-1 rounded-full bg-paper"
          style={{ color: MEDAL[place] }}
        />
      </span>
      <span className="mt-1.5 max-w-[7rem] truncate text-xs font-medium text-ink">{row.name}</span>
      <span className="figure-display text-[1.6rem] text-ink">{fmtScore(row.score)}</span>
      <StarRating value={row.stars} size="0.8rem" />
    </Person>
  );
}

/**
 * Ranked board in the light-panel look: title with a trophy in a tone-coloured pill (royal = parent
 * board, teal = child), podium for the top three, the rest as rows. Hover shows each factor's value /
 * weight / stars; click calls `onOpenStaff` (the STAFF_FILES / STAFF_CASES drawer). Search filters by
 * name or rank.
 */
export function Leaderboard({
  title,
  tone = "royal",
  data,
  loading,
  error,
  onRetry,
  onOpenStaff,
}: {
  title: string;
  /** royal = parent board, teal = child board. */
  tone?: "royal" | "teal";
  data: DashLeaderboard | undefined;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  onOpenStaff?: (row: DashLeaderboardRow) => void;
}) {
  const [search, setSearch] = React.useState("");
  const rows = data?.rows ?? [];
  const term = search.trim().toLowerCase();
  const filtered = term
    ? rows.filter((r) => r.name.toLowerCase().includes(term) || String(r.rank) === term)
    : rows;
  const showPodium = !term && rows.length >= 3;
  const podium = showPodium ? rows.slice(0, 3) : [];
  const list = showPodium ? rows.slice(3) : filtered;

  return (
    <section className="surface overflow-hidden">
      <header className="flex items-center justify-between gap-2 px-5 pb-1 pt-5">
        <span className="flex items-center gap-2.5">
          <span
            aria-hidden
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full border border-line bg-paper shadow-pill"
            style={{ color: TONE_TEXT[tone] }}
          >
            <Trophy size={15} />
          </span>
          <h3 className="m-0 font-sans text-base font-medium tracking-tight text-ink">{title}</h3>
        </span>
        <span className="flex items-center gap-1.5 rounded-full bg-grey-100 px-2.5 py-1 text-[11px] font-medium text-ink">
          <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: TONE_SOLID[tone] }} />
          {data?.members ?? rows.length} members
        </span>
      </header>

      <div className="p-5 pt-3">
        {error ? (
          <ErrorState error={error} onRetry={onRetry} className="py-4" />
        ) : loading ? (
          <Skeleton variant="line" rows={5} />
        ) : rows.length === 0 ? (
          <EmptyState title="No members on this board" className="py-4" />
        ) : (
          <>
            <label className="relative mb-3 flex items-center">
              <Search size={13} aria-hidden className="absolute left-2 text-muted" />
              <input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search by name or rank"
                aria-label={`Search ${title}`}
                className="h-9 w-full rounded-full border border-line bg-paper py-1.5 pl-7 pr-3 text-xs shadow-xs"
              />
            </label>

            {showPodium && (
              <div className="mb-4 grid grid-cols-3 items-end gap-2 rounded-2xl px-2 pb-3 pt-6" style={{ background: PODIUM_BG }}>
                <PodiumSpot row={podium[1]} place={2} onOpen={onOpenStaff} />
                <PodiumSpot row={podium[0]} place={1} onOpen={onOpenStaff} />
                <PodiumSpot row={podium[2]} place={3} onOpen={onOpenStaff} />
              </div>
            )}

            {list.length === 0 && term ? (
              <EmptyState title="No one matches that search" className="py-4" />
            ) : (
              <ul className="m-0 list-none space-y-1.5 p-0">
                {list.map((r) => {
                  const t = pctTone(r.score == null ? null : r.score / 10);
                  return (
                    <li key={r.staffId}>
                      <Person
                        row={r}
                        onOpen={onOpenStaff}
                        className={cn(
                          "flex items-center gap-3 rounded-xl border px-3 py-2",
                          r.self ? "border-navy bg-navy-tint" : "border-line bg-paper hover:bg-grey-50",
                        )}
                      >
                        <span
                          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-grey-100 text-[11px] font-semibold text-ink"
                          style={r.rank <= 3 ? { background: MEDAL[r.rank as 1 | 2 | 3] } : undefined}
                        >
                          {r.rank}
                        </span>
                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">
                          {r.name}
                          {r.self && <span className="ml-1.5 text-[10px] font-semibold text-navy">(you)</span>}
                        </span>
                        <StarRating value={r.stars} size="0.8rem" className="hidden sm:inline-flex" />
                        <span className="w-14 text-right text-sm font-semibold tabular-nums" style={{ color: PCT_TONE_COLOR[t] }}>
                          {fmtScore(r.score)}
                        </span>
                      </Person>
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}
      </div>
    </section>
  );
}
