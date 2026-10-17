"use client";

import * as React from "react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TabDef } from "@/components/ui/tabs";

export type PillTabDef = TabDef & { icon?: LucideIcon; disabled?: boolean };

/**
 * The customer pop-up's tab bar: compact underlined tabs that WRAP onto as many rows as they need,
 * so every tab is visible at once — no sideways scrolling (a mouse had no way to do it). The active
 * tab carries a navy underline and a light tint. Disabled tabs are inert. Styles: `.cust-tabs` in
 * globals.css.
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
  return (
    <div role="tablist" className={cn("cust-tabs", className)}>
      {tabs.map((t) => {
        const on = t.key === active;
        const Icon = t.icon;
        return (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={on}
            aria-disabled={t.disabled ? "true" : undefined}
            onClick={() => !t.disabled && onChange(t.key)}
            className={cn("cust-tab", on && "on", t.disabled && "off")}
          >
            {Icon && <Icon size={12} aria-hidden />}
            {t.label}
            {t.badge != null && <span className="cust-tab-badge">{t.badge}</span>}
          </button>
        );
      })}
    </div>
  );
}
