"use client";

import * as React from "react";
import { cn } from "@/lib/utils";
import { useFocusTrap } from "@/hooks/use-focus-trap";

/**
 * Right slide-over panel, sibling to {@link Dialog}. Used for the application
 * Journey (and any secondary detail surface) launched from a queue row.
 *
 * Behaviour: Escape and backdrop-click close; body scroll is locked while open;
 * the panel slides in from the right (full-screen sheet below `sm`). Unlike
 * Dialog it enforces a **focus trap** (Tab/Shift+Tab wrap) and **focus restore**
 * to the trigger on close, via {@link useFocusTrap}.
 *
 * Provide an accessible name with `aria-label` or `aria-labelledby`.
 */
export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  children?: React.ReactNode;
  className?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
}

export function Drawer({
  open,
  onClose,
  children,
  className,
  "aria-label": ariaLabel,
  "aria-labelledby": ariaLabelledBy,
}: DrawerProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open);
  const [entered, setEntered] = React.useState(false);

  // Escape closes.
  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Lock body scroll while open (scoped to this drawer).
  React.useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  // Drive the slide-in on the next frame after mount.
  React.useEffect(() => {
    if (!open) {
      setEntered(false);
      return;
    }
    const raf = requestAnimationFrame(() => setEntered(true));
    return () => cancelAnimationFrame(raf);
  }, [open]);

  if (!open) return null;

  return (
    <div
      className={cn(
        "fixed inset-0 z-[200] bg-navy/50 transition-opacity duration-300",
        entered ? "opacity-100" : "opacity-0",
      )}
      onClick={onClose}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledBy}
        tabIndex={-1}
        className={cn(
          "absolute inset-y-0 right-0 flex h-full w-full flex-col bg-white shadow-xl outline-none",
          "border-l border-line sm:max-w-md lg:max-w-lg",
          "transition-transform duration-300 ease-out",
          entered ? "translate-x-0" : "translate-x-full",
          className,
        )}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

export function DrawerHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("flex flex-col gap-1.5 border-b border-line px-5 py-4", className)}
      {...props}
    />
  );
}

export function DrawerTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn("font-serif text-xl text-navy", className)} {...props} />;
}

export function DrawerBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex-1 overflow-y-auto px-5 py-4", className)} {...props} />;
}

export function DrawerFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("flex justify-end gap-2 border-t border-line px-5 py-3", className)}
      {...props}
    />
  );
}
