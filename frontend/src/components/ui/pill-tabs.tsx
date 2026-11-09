"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TabDef } from "@/components/ui/tabs";

/** `groupStart` draws a thin divider above the tab, separating it from the group before. */
export type PillTabDef = TabDef & { icon?: LucideIcon; disabled?: boolean; groupStart?: boolean };

/**
 * The customer pop-up's tabs as a slim icon rail down the left: icon over a small label, one per
 * row, thin dividers between groups. It never wraps or scrolls sideways, and leaves the full width
 * to the content beside it; when the pop-up is short the rail scrolls on its own (mouse wheel).
 * The caller lays it out in a flex row with the tab body. Disabled tabs are inert and say why in
 * the tooltip. Styles: `.cust-rail` in globals.css.
 */
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
    activeRef.current?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [active]);

  return (
    <div role="tablist" aria-orientation="vertical" className={cn("cust-rail", className)}>
      {tabs.map((t) => {
        const on = t.key === active;
        const Icon = t.icon;
        return (
          <React.Fragment key={t.key}>
            {t.groupStart && <span className="cust-rail-div" aria-hidden />}
            <button
              ref={on ? activeRef : undefined}
              type="button"
              role="tab"
              aria-selected={on}
              aria-disabled={t.disabled ? "true" : undefined}
              title={typeof t.label === "string" ? (t.badge != null ? `${t.label} · ${t.badge}` : t.label) : undefined}
              onClick={() => !t.disabled && onChange(t.key)}
              className={cn("cust-rail-item", on && "on", t.disabled && "off")}
            >
              {Icon && <Icon size={15} aria-hidden />}
              <span>{t.label}</span>
            </button>
          </React.Fragment>
        );
      })}
    </div>
  );
}
