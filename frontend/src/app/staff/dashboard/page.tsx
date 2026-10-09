"use client";

import * as React from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQueryClient, useIsFetching } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/live-pipeline";
import { PeriodPicker } from "@/components/staff/period-picker";
import { QueueStrip } from "@/components/staff/dashboard/queue-strip";
import { RoleToggleStrip } from "@/components/staff/dashboard/role-toggle-strip";
import { RecordsDrawer, useRecordsTarget } from "@/components/staff/dashboard/records-drawer";
import {
  ADMIN_TABS,
  ADMIN_TAB_REGISTRY,
  ROLE_VIEW_REGISTRY,
  type AdminTabId,
} from "@/components/staff/dashboard/registry";
import { allowedViews, resolveView } from "@/components/staff/dashboard/views";
import { fmtDayLong, todayIso } from "@/components/staff/dashboard/fmt";
import type { DashParams, DashView } from "@/lib/api/applications";
import { useStaffSession } from "@/lib/auth/staff-session";
import { STAFF_ROLE_LABELS } from "@/lib/auth/rbac";
import { rangeFor, type Range } from "@/lib/period";
import { useMounted } from "@/hooks/use-mounted";
import { cn } from "@/lib/utils";

/**
 * When "All time" is picked there is no from/to; the analytics endpoints still want a window, and the
 * backend rejects a span over 1100 days (INVALID_RANGE) -- so "all time" is the last 1095 days.
 */
const ALL_TIME_DAYS = 1095;
const allTimeFrom = (): string => {
  const d = new Date();
  d.setDate(d.getDate() - ALL_TIME_DAYS);
  return todayIso(d);
};
const DEFAULT_PRESET = "this-month";

function Dashboard() {
  const mounted = useMounted();
  const { session } = useStaffSession();
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const queryClient = useQueryClient();
  const fetching =
    useIsFetching({ predicate: (q) => String(q.queryKey[0]).startsWith("staff-dashboard-") && q.queryKey[0] !== "staff-dashboard-records" }) > 0;
  const { target, open, close } = useRecordsTarget();

  const preset = sp.get("preset") ?? DEFAULT_PRESET;
  const custom = React.useMemo<Range>(
    () => ({ from: sp.get("from") ?? undefined, to: sp.get("to") ?? undefined }),
    [sp],
  );
  const range = React.useMemo(() => rangeFor(preset, custom), [preset, custom]);

  const setUrl = React.useCallback(
    (patch: Record<string, string | null>) => {
      const next = new URLSearchParams(sp.toString());
      for (const [k, v] of Object.entries(patch)) {
        if (v == null || v === "") next.delete(k);
        else next.set(k, v);
      }
      const qs = next.toString();
      router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    },
    [sp, router, pathname],
  );

  const realRole = session?.realRole;
  const workingRole = session?.role;
  const view = realRole && workingRole ? resolveView(realRole, workingRole, sp.get("view")) : null;

  const params = React.useMemo<DashParams | null>(
    () => (view ? { view, from: range.from ?? allTimeFrom(), to: range.to ?? todayIso() } : null),
    [view, range.from, range.to],
  );

  if (!mounted || !session || !realRole || !workingRole) {
    return <div className="h-64 rounded border border-line bg-white" />;
  }

  // DSA is a firewalled portal role with no dashboard permission at all; the backend is the real guard.
  if (realRole === "DSA" || !view || !params) {
    return <NoAccessNotice message="DSAs use the leads and earnings pages — see the DSA menu." />;
  }

  const periodLabel = `${fmtDayLong(params.from)} – ${fmtDayLong(params.to)}`;
  const views = allowedViews(realRole);
  const tab = (ADMIN_TABS.find((t) => t.id === sp.get("tab"))?.id ?? "snapshot") as AdminTabId;
  const tabProps = { params, open, realAdmin: realRole === "ADMIN", periodLabel };
  const Body = view === "ADMIN" ? ADMIN_TAB_REGISTRY[tab] : ROLE_VIEW_REGISTRY[view];

  const refresh = () =>
    void queryClient.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).startsWith("staff-dashboard-") });

  return (
    <div>
      <PageHeader
        title={`Welcome, ${session.name.split(" ")[0]}`}
        subtitle={`${STAFF_ROLE_LABELS[workingRole]} · live business analytics`}
      >
        <button
          type="button"
          onClick={refresh}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {fetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <QueueStrip role={workingRole} staffId={session.id} />

      <RoleToggleStrip views={views} active={view} onChange={(v: DashView) => setUrl({ view: v, tab: null })} />

      <PeriodPicker
        preset={preset}
        onPreset={(p) => setUrl({ preset: p === DEFAULT_PRESET ? null : p, from: null, to: null })}
        custom={custom}
        onCustom={(r) => setUrl({ preset: "custom", from: r.from ?? null, to: r.to ?? null })}
      />

      {view === "ADMIN" && (
        <div
          role="tablist"
          aria-label="Admin dashboard sections"
          className="mb-4 flex gap-1 overflow-x-auto rounded-xl bg-navy p-1.5"
        >
          {ADMIN_TABS.map((t) => {
            const on = t.id === tab;
            return (
              <button
                key={t.id}
                type="button"
                role="tab"
                aria-selected={on}
                onClick={() => setUrl({ tab: t.id === "snapshot" ? null : t.id })}
                className={cn(
                  "shrink-0 whitespace-nowrap rounded-lg px-3.5 py-1.5 text-xs font-semibold transition",
                  on ? "bg-white text-navy shadow" : "text-white/80 hover:bg-white/10 hover:text-white",
                )}
              >
                {t.label}
              </button>
            );
          })}
        </div>
      )}

      <Body {...tabProps} />

      <RecordsDrawer target={target} onClose={close} params={params} periodLabel={periodLabel} />
    </div>
  );
}

export default function StaffDashboardPage() {
  return (
    <React.Suspense fallback={<div className="h-64 rounded border border-line bg-white" />}>
      <Dashboard />
    </React.Suspense>
  );
}
