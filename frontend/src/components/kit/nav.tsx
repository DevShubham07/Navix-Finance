"use client";

import * as React from "react";
import Link from "next/link";
import { cn } from "@/lib/utils";
import { Avatar } from "./viz";

/**
 *   <PillNav>        the reference's top nav: white pills, active one black
 *   <UnderlineTabs>  "⑩ Revenue Metrics | ⑧ KPI Widgets" — count badge + ink underline
 *   <UserChip>       avatar + name + role
 */

export interface PillNavItem {
  href: string;
  label: string;
  icon?: React.ReactNode;
}

export function PillNav({ items, activeHref, className }: { items: PillNavItem[]; activeHref: string; className?: string }) {
  return (
    <nav className={cn("pill-tabs flex items-center gap-2 p-1 md:![mask-image:none] md:![-webkit-mask-image:none]", className)} aria-label="Primary">
      {items.map((it) => {
        const active = it.href === activeHref;
        return (
          <Link key={it.href} href={it.href} aria-current={active ? "page" : undefined} className={cn("nav-pill", active && "is-active")}>
            {it.icon}
            {it.label}
          </Link>
        );
      })}
    </nav>
  );
}

export function UnderlineTabs<T extends string>({
  tabs,
  value,
  onChange,
  className,
}: {
  tabs: readonly { value: T; label: string; count?: number }[];
  value: T;
  onChange: (v: T) => void;
  className?: string;
}) {
  return (
    <div role="tablist" className={cn("flex border-b border-line", className)}>
      {tabs.map((t) => {
        const active = t.value === value;
        return (
          <button
            key={t.value}
            role="tab"
            type="button"
            aria-selected={active}
            onClick={() => onChange(t.value)}
            className={cn(
              "-mb-px flex flex-1 items-center gap-2 border-b-2 px-2 pb-3 pt-1 text-[0.72rem] font-medium transition-colors",
              active ? "border-ink text-ink" : "border-transparent text-muted hover:text-ink",
            )}
          >
            {t.count != null && (
              <span
                className={cn(
                  "grid h-[18px] min-w-[18px] place-items-center rounded-full px-1 text-[9px] font-semibold tabular-nums",
                  active ? "bg-navy text-white" : "bg-grey-200 text-slate",
                )}
              >
                {t.count}
              </span>
            )}
            {t.label}
          </button>
        );
      })}
    </div>
  );
}

export function UserChip({ name, role, className }: { name: string; role: string; className?: string }) {
  return (
    <span className={cn("flex items-center gap-2.5", className)}>
      <Avatar name={name} size={40} />
      <span className="hidden leading-tight sm:block">
        <span className="block text-[0.76rem] font-medium text-ink">{name}</span>
        <span className="block text-[0.6rem] text-muted">{role}</span>
      </span>
    </span>
  );
}
