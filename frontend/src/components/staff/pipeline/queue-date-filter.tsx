"use client";

/**
 * One global Today/Yesterday/Custom/All-time date filter, plus the page-wide search box, for the
 * live-applications page — both narrow every stage panel at once (product decision: page-wide
 * controls, not one per panel). {@link QueueRangeProvider} holds the current `{ range, query }`;
 * {@link useQueueRange}/{@link useQueueQuery} read their half from any panel/query — kept as two
 * hooks (rather than one that returns the whole object) so the many existing `range.from`/`range.to`
 * call sites didn't have to change shape when `query` was added. {@link QueueDateFilter} is the date
 * control itself; the search `<input>` lives on the page (`app/staff/applications/page.tsx`).
 */

import * as React from "react";
import { Input } from "@/components/ui";

export type QueuePeriod = "ALL" | "TODAY" | "YESTERDAY" | "CUSTOM";

export interface QueueRange {
  from?: string;
  to?: string;
}

/** The page-wide filter: the date window plus the (already-debounced) search term. */
export interface QueueFilter {
  range: QueueRange;
  /** Narrows WITHIN `range` — name/mobile/PAN/application/loan #. Must never bypass the date filter. */
  query: string;
}

const Ctx = React.createContext<QueueFilter>({ range: {}, query: "" });

/** Read the current global date range — include `range.from`/`range.to` in any query key that
 *  consumes it, or stale results get served across a filter change. */
export function useQueueRange(): QueueRange {
  return React.useContext(Ctx).range;
}

/** Read the current global search term — fold it into any query key that consumes it, same as
 *  `range.from`/`range.to`, or a filter change serves stale rows. */
export function useQueueQuery(): string {
  return React.useContext(Ctx).query;
}

export function QueueRangeProvider({
  value,
  children,
}: {
  value: QueueFilter;
  children: React.ReactNode;
}) {
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** Local-time ISO yyyy-mm-dd — never UTC-shifts at a day boundary (mirrors the transactions ledger). */
export function toLocalISO(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** The inclusive `[from, to]` ISO window for a period, or `{}` for "all time"/"custom" (custom is
 *  whatever the caller has typed into the two date inputs). */
export function rangeFor(period: QueuePeriod, custom: QueueRange): QueueRange {
  const now = new Date();
  switch (period) {
    case "TODAY": {
      const t = toLocalISO(now);
      return { from: t, to: t };
    }
    case "YESTERDAY": {
      const y = toLocalISO(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1));
      return { from: y, to: y };
    }
    case "CUSTOM":
      return custom;
    default:
      return {};
  }
}

const PERIODS: { key: QueuePeriod; label: string }[] = [
  { key: "ALL", label: "All time" },
  { key: "TODAY", label: "Today" },
  { key: "YESTERDAY", label: "Yesterday" },
  { key: "CUSTOM", label: "Custom" },
];

export function QueueDateFilter({
  period,
  setPeriod,
  custom,
  setCustom,
}: {
  period: QueuePeriod;
  setPeriod: (p: QueuePeriod) => void;
  custom: QueueRange;
  setCustom: (r: QueueRange) => void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex flex-wrap gap-1 rounded border border-line bg-grey-50 p-1">
        {PERIODS.map((p) => (
          <button
            key={p.key}
            type="button"
            onClick={() => setPeriod(p.key)}
            className={`rounded px-2.5 py-1 text-xs font-semibold transition-colors ${
              period === p.key ? "bg-navy text-white" : "text-muted hover:text-ink"
            }`}
          >
            {p.label}
          </button>
        ))}
      </div>
      {period === "CUSTOM" ? (
        <div className="flex items-center gap-2">
          <Input
            type="date"
            value={custom.from ?? ""}
            onChange={(e) => setCustom({ ...custom, from: e.target.value || undefined })}
            className="!mb-0 !w-40"
            aria-label="From date"
          />
          <span className="text-xs text-muted">to</span>
          <Input
            type="date"
            value={custom.to ?? ""}
            onChange={(e) => setCustom({ ...custom, to: e.target.value || undefined })}
            className="!mb-0 !w-40"
            aria-label="To date"
          />
        </div>
      ) : null}
    </div>
  );
}
