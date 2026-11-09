"use client";

/**
 * The page-wide date window (Today / Yesterday / Custom) held in the URL, so a refresh, a shared link
 * and the back button all keep it. Three params: `win` (TODAY | YESTERDAY | CUSTOM, absent = all
 * time), and `wfrom` / `wto` for a custom range. The relative periods are stored as the period, not
 * as dates, so a link that says "Today" still means today tomorrow.
 *
 * It only writes its own params and drops `page` in the same replace, so a changed window starts at
 * page 1 without a second writer touching the address bar. (A register that owns the URL —
 * `useRegisterQuery` — resets its page from {@link windowKey} during render; see the Customers page.)
 */

import * as React from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import {
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Read the window out of a query string. Pure, for the tests. */
export function readDateWindow(params: URLSearchParams): { period: QueuePeriod; custom: QueueRange } {
  const win = params.get("win");
  if (win === "TODAY" || win === "YESTERDAY") return { period: win, custom: {} };
  if (win === "CUSTOM") {
    const from = params.get("wfrom") ?? "";
    const to = params.get("wto") ?? "";
    return { period: "CUSTOM", custom: { from: DAY.test(from) ? from : undefined, to: DAY.test(to) ? to : undefined } };
  }
  return { period: "ALL", custom: {} };
}

/** The query string with the window written (or cleared) and `page` dropped. Pure, for the tests. */
export function writeDateWindow(
  current: string,
  period: QueuePeriod,
  custom: QueueRange,
  /** Keep `page`: the window being written selects the same rows as the one already there. */
  keepPage = false,
): string {
  const p = new URLSearchParams(current);
  p.delete("win");
  p.delete("wfrom");
  p.delete("wto");
  if (!keepPage) p.delete("page");
  if (period !== "ALL") p.set("win", period);
  if (period === "CUSTOM") {
    if (custom.from) p.set("wfrom", custom.from);
    if (custom.to) p.set("wto", custom.to);
  }
  return p.toString();
}

export function useUrlDateWindow() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const qs = searchParams.toString();

  const { period, custom } = React.useMemo(() => readDateWindow(new URLSearchParams(qs)), [qs]);
  const range = React.useMemo(() => rangeFor(period, custom), [period, custom]);

  const write = React.useCallback(
    (nextPeriod: QueuePeriod, nextCustom: QueueRange) => {
      // All time and a Custom range with nothing in it select the same rows: no reason to lose the page.
      const nextRange = rangeFor(nextPeriod, nextCustom);
      const same = (nextRange.from ?? "") === (range.from ?? "") && (nextRange.to ?? "") === (range.to ?? "");
      const next = writeDateWindow(qs, nextPeriod, nextCustom, same);
      router.replace(next ? `${pathname}?${next}` : pathname, { scroll: false });
    },
    [qs, pathname, router, range.from, range.to],
  );
  const setPeriod = React.useCallback((p: QueuePeriod) => write(p, p === "CUSTOM" ? custom : {}), [write, custom]);
  const setCustom = React.useCallback((c: QueueRange) => write("CUSTOM", c), [write]);

  /** Changes exactly when the selected rows would: use it to send a register back to page 1. */
  const windowKey = `${range.from ?? ""}|${range.to ?? ""}`;
  return { period, custom, range, setPeriod, setCustom, windowKey };
}
