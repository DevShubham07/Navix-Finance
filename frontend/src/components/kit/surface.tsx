"use client";

import * as React from "react";
import { SlidersHorizontal } from "lucide-react";
import { cn } from "@/lib/utils";
import { Dropdown, type DropdownOption } from "./menu";

/**
 * Surfaces — the building blocks every dashboard card is made of.
 *
 *   <Panel>            near-white card (theme `--surface`), 22px radius, soft shadow + white edge
 *   <Panel tone="dark"> the black feature card ("Bounce rate")
 *   <PanelHeader>      title (+ optional eyebrow/subtitle) left, controls right
 *   <PillSelect>       the "Week ⌄" pill
 *   <IconPill>         round white icon button (filters, bell, help)
 *   <Tile>             white inner tile inside a panel
 */

export function Panel({
  tone = "light",
  className,
  ...props
}: React.HTMLAttributes<HTMLElement> & { tone?: "light" | "dark" }) {
  return (
    <section
      className={cn(
        "relative min-w-0 p-5",
        // Only the dark card clips (its texture bleeds); light panels must let menus/tooltips overflow.
        tone === "dark" ? "surface-dark overflow-hidden" : "surface",
        className,
      )}
      {...props}
    />
  );
}

export function PanelHeader({
  title,
  subtitle,
  controls,
  className,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  controls?: React.ReactNode;
  className?: string;
}) {
  return (
    <header className={cn("mb-4 flex items-start justify-between gap-3", className)}>
      <div className="min-w-0">
        <h3 className="m-0 text-base font-medium tracking-tight text-inherit">{title}</h3>
        {subtitle && <p className="m-0 mt-1 text-xs text-muted">{subtitle}</p>}
      </div>
      {controls && <div className="flex shrink-0 items-center gap-2">{controls}</div>}
    </header>
  );
}

/** Dark mini card riding on a light footer label ("Reminders", "Automatizations" in the reference). */
export function FooterCard({
  footer,
  className,
  children,
}: {
  footer: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={cn("rounded-[20px] border border-line bg-grey-100 p-1 shadow-xs", className)}>
      <div className="rounded-[17px] bg-navy p-3.5 text-white shadow-md">{children}</div>
      <p className="m-0 px-3 pb-1.5 pt-2.5 text-xs text-muted">{footer}</p>
    </div>
  );
}

export function Tile({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("surface-tile p-4", className)} {...props} />;
}

export function IconPill({
  className,
  children,
  label,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button type="button" aria-label={label} title={label} className={cn("icon-pill", className)} {...props}>
      {children}
    </button>
  );
}

/** The "filters" icon pill that sits beside a PillSelect. */
export function FilterPill(props: Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "children">) {
  return (
    <IconPill label="Filters" {...props}>
      <SlidersHorizontal size={15} strokeWidth={1.8} />
    </IconPill>
  );
}

/** The reference's "Week ⌄" pill — a themed, animated listbox (see <Dropdown>). */
export function PillSelect<T extends string>({
  value,
  onChange,
  options,
  label,
  className,
}: {
  value: T;
  onChange: (v: T) => void;
  options: readonly DropdownOption<T>[];
  label: string;
  className?: string;
}) {
  return <Dropdown value={value} onChange={onChange} options={options} label={label} className={className} />;
}
