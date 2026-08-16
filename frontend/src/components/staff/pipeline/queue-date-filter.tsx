"use client";

/**
 * One global Today/Yesterday/Custom/All-time date filter for the live-applications page —
 * narrows every stage panel at once (product decision: a single page-wide control, not one per
 * panel). {@link QueueRangeProvider} holds the current range; {@link useQueueRange} reads it from
 * any panel/query; {@link QueueDateFilter} is the control itself.
 */

import * as React from "react";
import { Input } from "@/components/ui";

export type QueuePeriod = "ALL" | "TODAY" | "YESTERDAY" | "CUSTOM";

export interface QueueRange {
  from?: string;
  to?: string;
}

const Ctx = React.createContext<QueueRange>({});

/** Read the current global date range — include `range.from`/`range.to` in any query key that
 *  consumes it, or stale results get served across a filter change. */
export function useQueueRange(): QueueRange {
  return React.useContext(Ctx);
}

export function QueueRangeProvider({
  value,
  children,
}: {
  value: QueueRange;
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
