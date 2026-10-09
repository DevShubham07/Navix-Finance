"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TabDef } from "@/components/ui/tabs";

export type PillTabDef = TabDef & { icon?: LucideIcon; disabled?: boolean };

/** One-row scrolling pill tabs (navy fill when active). Disabled tabs are inert. */
export function PillTabs({
  tabs,
  active,
  onChange,
  className,
}: {
  tabs: PillTabDef[];
  active: string;
  onChange: (key: string) => void;
  className?: string;
}) {
  const activeRef = React.useRef<HTMLButtonElement>(null);
  React.useEffect(() => {
    activeRef.current?.scrollIntoView?.({ inline: "nearest", block: "nearest" });
  }, [active]);

  return (
    <div className={cn("pill-tabs", className)}>
      <div role="tablist" className="flex w-max gap-1.5 px-4 py-1">
        {tabs.map((t) => {
          const on = t.key === active;
          const Icon = t.icon;
          return (
            <button
              key={t.key}
              ref={on ? activeRef : undefined}
              type="button"
              role="tab"
              aria-selected={on}
              aria-disabled={t.disabled ? "true" : undefined}
              onClick={() => !t.disabled && onChange(t.key)}
              className={cn(
                "cal-preset flex items-center gap-1.5 whitespace-nowrap",
                on && "on",
                t.disabled && "cursor-not-allowed opacity-50 hover:!bg-white hover:![color:var(--slate)] hover:![border-color:var(--line-2)]",
              )}
            >
              {Icon && <Icon size={13} />}
              {t.label}
              {t.badge != null && (
                <span className="rounded-full bg-navy-tint px-1.5 py-0.5 text-[8px] font-semibold text-navy">
                  {t.badge}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
