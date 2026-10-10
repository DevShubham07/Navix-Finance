"use client";

import * as React from "react";
import { EmptyState, ErrorState, InfoTooltip, Skeleton } from "@/components/ui";
import { cn } from "@/lib/utils";

export interface ChartCardProps {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  /** One-line metric definition shown in the ⓘ tooltip. */
  info?: React.ReactNode;
  /** Right-side control(s): month select, toggle, button. */
  controls?: React.ReactNode;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** True when the query succeeded but there is nothing to draw. */
  empty?: boolean;
  emptyTitle?: string;
  /** Series / domain colour, shown as a small dot before the title. */
  accent?: string;
  className?: string;
  bodyClassName?: string;
  children?: React.ReactNode;
}

/**
 * Widget shell in the kit's Panel look (`.surface`: near-white, 22px radius, soft shadow; title in
 * medium-weight body type). Loading / error / empty are handled INSIDE the card so one failed widget
 * never blanks the page.
 */
export function ChartCard({
  title,
  subtitle,
  info,
  controls,
  loading,
  error,
  onRetry,
  empty,
  emptyTitle = "No data for this period",
  accent,
  className,
  bodyClassName,
  children,
}: ChartCardProps) {
  const failed = error != null && error !== false;
  return (
    <section className={cn("surface relative min-w-0 overflow-hidden p-5", className)}>
      <header className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            {accent && <span aria-hidden className="h-2 w-2 shrink-0 rounded-full" style={{ background: accent }} />}
            <h3 className="m-0 font-sans text-base font-medium tracking-tight text-ink">{title}</h3>
            {info && <InfoTooltip content={info} />}
          </div>
          {subtitle && <p className="m-0 mt-1 text-xs text-muted">{subtitle}</p>}
        </div>
        {controls && <div className="flex flex-wrap items-center gap-2">{controls}</div>}
      </header>
      <div className={bodyClassName}>
        {failed ? (
          <ErrorState error={error} onRetry={onRetry} className="py-6" />
        ) : loading ? (
          <Skeleton variant="line" rows={5} />
        ) : empty ? (
          <EmptyState title={emptyTitle} className="py-6" />
        ) : (
          children
        )}
      </div>
    </section>
  );
}

/** A `<section>` heading used between groups of cards ("Key Performance Indicators"). */
export function SectionTitle({ children }: { children: React.ReactNode }) {
  return <h2 className="mb-3 mt-8 font-sans text-base font-medium tracking-tight text-ink first:mt-0">{children}</h2>;
}
