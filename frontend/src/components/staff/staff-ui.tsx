"use client";

import * as React from "react";
import { useQueryClient, useIsFetching } from "@tanstack/react-query";
import { RefreshCw, Loader2 } from "lucide-react";
import { InfoTooltip } from "@/components/ui";
import { cn } from "@/lib/utils";

/** Page title + subtitle + optional right-aligned actions. */
export function PageHeader({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="mb-0 text-2xl lg:text-3xl">{title}</h1>
        {subtitle ? <p className="mt-1 text-muted">{subtitle}</p> : null}
      </div>
      {children ? <div className="flex flex-wrap items-center gap-2">{children}</div> : null}
    </div>
  );
}

/**
 * Page-level refresh: invalidates every react-query key backing a pipeline page
 * (all the composed queue panels at once), and reflects in-flight fetches with a
 * spinner keyed off the first query key. Uses the established inline-toolbar style
 * shared by the per-panel refreshers.
 */
export function RefreshButton({
  queryKeys,
  label = "Refresh",
}: {
  queryKeys: unknown[][];
  label?: string;
}) {
  const qc = useQueryClient();
  const fetching = useIsFetching({ queryKey: queryKeys[0] });
  return (
    <button
      type="button"
      onClick={() => queryKeys.forEach((k) => qc.invalidateQueries({ queryKey: k }))}
      className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
    >
      {fetching > 0 ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />}
      {label}
    </button>
  );
}

/** Compact metric tile for dashboards. */
export function StatCard({
  label,
  value,
  hint,
  accent,
  info,
}: {
  label: string;
  value: React.ReactNode;
  hint?: string;
  accent?: "navy" | "gold" | "success" | "error";
  /** Optional ⓘ explanation (top-right) so new staff know what this metric means. */
  info?: string;
}) {
  const ring =
    accent === "gold" ? "border-gold-soft" :
    accent === "success" ? "border-success-100" :
    accent === "error" ? "border-error-100" : "border-line";
  return (
    <div className={cn("rounded border bg-white p-5 shadow-sm", ring)}>
      <div className="flex items-start justify-between gap-2">
        <div className="text-sm text-muted">{label}</div>
        {info ? <InfoTooltip content={info} /> : null}
      </div>
      {/* `tabular-nums` so a tile's figure does not jitter in width as a poll changes its digits,
          and so a row of tiles reads as aligned figures. `null`/`undefined` renders an em dash:
          an unmeasured value must never read as a measured zero, which is exactly how the
          performance page's tiles misled during every period change. */}
      <div className="mt-1 font-serif text-2xl font-bold tabular-nums text-navy lg:text-3xl">
        {value ?? "—"}
      </div>
      {hint ? <div className="mt-1 text-xs text-muted">{hint}</div> : null}
    </div>
  );
}
