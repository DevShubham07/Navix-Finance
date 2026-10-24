"use client";

/**
 * Tabbed desks for the staff console: a row of clickable queue tabs, each carrying a live count, so
 * a staffer sees at a glance which of their queues has something in it without scrolling a stack of
 * panels — plus the read-only "past cases" panels (sanctioned, rejected, disbursed, closed) that sit
 * beside the live desks.
 *
 * Counts share the exact React Query keys {@link StatusQueue} uses (`["staff-queue", status, from,
 * to, q]`), so a tab's badge and the panel under it are one request, not two.
 */

import * as React from "react";
import { useQueries } from "@tanstack/react-query";
import { staffApi, type ApplicationStatus, type ApplicationView } from "@/lib/api/applications";
import { QueuePanel } from "@/components/staff/pipeline/status-queue";
import { useQueueRange, useQueueQuery } from "@/components/staff/pipeline/queue-date-filter";
import { useQueueRegister } from "@/components/staff/pipeline/queue-register";
import { cn } from "@/lib/utils";

/** Post-sanction statuses: the credit decision went through and the file moved on. */
export const PAST_SANCTIONED_STATUSES: ApplicationStatus[] = [
  "DISBURSEMENT_PENDING",
  "DISBURSEMENT_FAILED",
  "DISBURSED",
  "ACTIVE",
  "OVERDUE",
  "DEFAULTED",
  "CLOSED",
  "WRITTEN_OFF",
];

/** Statuses a released loan can be in. */
export const PAST_DISBURSED_STATUSES: ApplicationStatus[] = [
  "DISBURSED",
  "ACTIVE",
  "OVERDUE",
  "DEFAULTED",
  "CLOSED",
  "WRITTEN_OFF",
];

const byNewest = (a: ApplicationView, b: ApplicationView) =>
  (b.createdAt ?? "").localeCompare(a.createdAt ?? "") || b.id - a.id;

/**
 * Applications across one or more statuses, inside the page-wide date window + search box.
 * `poll` defaults to 8s (a live desk); history panels pass a slower interval.
 */
export function useStatusApps(
  statuses: ApplicationStatus[],
  opts: { mine?: boolean; filter?: (a: ApplicationView) => boolean; poll?: number } = {},
) {
  const range = useQueueRange();
  const query = useQueueQuery();
  const { mine, filter, poll = 8000 } = opts;
  const results = useQueries({
    queries: statuses.map((status) => ({
      queryKey: ["staff-queue", status, range.from ?? "", range.to ?? "", query, ...(mine ? ["mine"] : [])],
      queryFn: () => staffApi.listByStatus(status, range, query || undefined, mine ? { mine } : undefined),
      refetchInterval: poll,
    })),
  });
  const datas = results.map((r) => r.data);
  const apps = React.useMemo(() => {
    const all = datas.flatMap((d) => d ?? []);
    return (filter ? all.filter(filter) : all).sort(byNewest);
    // `datas` entries keep their reference across an unchanged poll, so this re-runs only on change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...datas, filter]);
  return {
    apps,
    isLoading: results.some((r) => r.isLoading),
    error: results.find((r) => r.error)?.error ?? null,
    refetch: () => results.forEach((r) => void r.refetch()),
  };
}

export type QueueTabDef = {
  key: string;
  label: string;
  /** Live count; `undefined` while it is still loading. */
  count?: number;
  /** A live desk (work waiting on you) highlights a non-zero count; a history tab stays muted. */
  live?: boolean;
};

/** The row of clickable queue tabs, each with its count. */
export function QueueTabs({
  tabs,
  active,
  onChange,
}: {
  tabs: QueueTabDef[];
  active: string;
  onChange: (key: string) => void;
}) {
  return (
    <div role="tablist" className="flex flex-wrap gap-2">
      {tabs.map((t) => {
        const on = t.key === active;
        const hot = t.live && (t.count ?? 0) > 0;
        return (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={on}
            onClick={() => onChange(t.key)}
            className={cn(
              "flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-semibold transition",
              on ? "border-navy bg-navy text-white" : "border-line bg-white text-navy hover:bg-grey-100",
            )}
          >
            {t.label}
            <span
              className={cn(
                "min-w-[1.5rem] rounded-full px-2 py-0.5 text-center text-xs font-semibold",
                hot ? "bg-gold text-white" : on ? "bg-white/20 text-white" : "bg-navy-tint text-navy",
              )}
            >
              {t.count ?? "…"}
            </span>
          </button>
        );
      })}
    </div>
  );
}

/** A tab's selection, remembered per desk for the session so a poll or a refresh keeps the tab. */
export function useQueueTab(desk: string, fallback: string) {
  const storageKey = `navix-staff-queue-tab:${desk}`;
  const [tab, setTab] = React.useState<string>(() => {
    try {
      return sessionStorage.getItem(storageKey) ?? fallback;
    } catch {
      return fallback;
    }
  });
  const change = React.useCallback(
    (next: string) => {
      setTab(next);
      try {
        sessionStorage.setItem(storageKey, next);
      } catch {
        /* storage unavailable — the tab still switches */
      }
    },
    [storageKey],
  );
  return [tab, change] as const;
}

/** A read-only "past cases" panel over several statuses (no row actions). */
export function HistoryQueue({
  title,
  registerId,
  data,
  info,
}: {
  title: string;
  registerId: string;
  data: ReturnType<typeof useStatusApps>;
  info?: string;
}) {
  const register = useQueueRegister(data.apps, registerId);
  return (
    <QueuePanel
      title={title}
      countBadge={title}
      apps={data.apps}
      isLoading={data.isLoading}
      error={data.error}
      onRefresh={data.refetch}
      actions={() => null}
      info={info}
      register={register}
    />
  );
}
