"use client";

import * as React from "react";
import { Info } from "lucide-react";
import { cn } from "@/lib/utils";

export interface InfoTooltipProps {
  /** Short explanation shown in the popover. */
  content: React.ReactNode;
  /** Extra classes for the trigger wrapper. */
  className?: string;
  /** Accessible label for the trigger button. */
  label?: string;
  /** Icon size in px. */
  size?: number;
}

/**
 * A small ⓘ info trigger that reveals a short explanation on hover or click.
 * Dependency-free and design-token styled. Drop it at the top-right of a card /
 * column header so newly-onboarded staff understand what a section does.
 */
export function InfoTooltip({ content, className, label = "More information", size = 14 }: InfoTooltipProps) {
  const [open, setOpen] = React.useState(false);
  const ref = React.useRef<HTMLSpanElement | null>(null);

  React.useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onEsc = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onEsc);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onEsc);
    };
  }, [open]);

  return (
    <span
      ref={ref}
      className={cn("relative inline-flex", className)}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        aria-label={label}
        aria-expanded={open}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setOpen((o) => !o);
        }}
        className="inline-flex items-center justify-center rounded-full text-muted transition-colors hover:text-navy focus:outline-none focus-visible:text-navy"
      >
        <Info size={size} strokeWidth={2.25} />
      </button>
      {open && (
        <span
          role="tooltip"
          className="absolute right-0 top-[calc(100%+6px)] z-50 w-max max-w-[16rem] rounded border border-line bg-white p-2.5 text-left text-xs font-normal leading-snug text-ink shadow-md"
        >
          {content}
        </span>
      )}
    </span>
  );
}
