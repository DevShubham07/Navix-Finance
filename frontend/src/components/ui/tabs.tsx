"use client";

import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Minimal accessible tabs. Controlled by the parent so the active tab can persist across
 * content swaps (e.g. the customer detail popup keeps its tab when another row is opened).
 */
export interface TabDef {
  key: string;
  label: React.ReactNode;
  /** Optional small count/badge shown after the label. */
  badge?: React.ReactNode;
}

export function Tabs({
  tabs,
  active,
  onChange,
  className,
}: {
  tabs: TabDef[];
  active: string;
  onChange: (key: string) => void;
  className?: string;
}) {
  return (
    <div role="tablist" className={cn("flex flex-wrap gap-1 border-b border-line", className)}>
      {tabs.map((t) => {
        const on = t.key === active;
        return (
          <button
            key={t.key}
            role="tab"
            aria-selected={on}
            onClick={() => onChange(t.key)}
            className={cn(
              "-mb-px flex items-center gap-1.5 border-b-2 px-3 py-2 text-xs font-semibold transition",
              on
                ? "border-navy text-navy"
                : "border-transparent text-muted hover:border-line hover:text-ink",
            )}
          >
            {t.label}
            {t.badge != null && (
              <span className="rounded-full bg-navy-tint px-1.5 py-0.5 text-[10px] font-semibold text-navy">
                {t.badge}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
