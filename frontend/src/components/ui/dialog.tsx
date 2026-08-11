"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/utils";

/**
 * Lightweight modal using the design's `.modal-overlay` / `.modal` styling.
 * Used for confirm actions such as maker-checker approvals and disbursement
 * release. Closes on overlay click and Escape.
 *
 * Rendered through a portal to `document.body`. Call sites legitimately mount a dialog deep inside
 * the tree — the staff queue rows open theirs from inside a `position: sticky` table cell, which
 * creates a stacking context that trapped the overlay *below* the sticky table header (the navy
 * header bars painted straight over the open modal). A portal takes the overlay out of every
 * ancestor's stacking context, so its `z-index: 200` means what it says.
 */
export interface DialogProps {
  open: boolean;
  onClose: () => void;
  children?: React.ReactNode;
  className?: string;
  /** Accessible name for the dialog (use one of the two). */
  "aria-label"?: string;
  "aria-labelledby"?: string;
}

export function Dialog({
  open,
  onClose,
  children,
  className,
  "aria-label": ariaLabel,
  "aria-labelledby": ariaLabelledby,
}: DialogProps) {
  // Portals need a DOM target, which doesn't exist during SSR / the first render pass.
  const [mounted, setMounted] = React.useState(false);
  React.useEffect(() => setMounted(true), []);

  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open || !mounted) return null;
  return createPortal(
    <div className="modal-overlay show" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledby}
        className={cn("modal", className)}
        style={{ textAlign: "left" }}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>,
    document.body,
  );
}

export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("mb-4 flex flex-col gap-1.5", className)} {...props} />;
}

export function DialogTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h3 className={cn("font-serif text-xl text-navy", className)} {...props} />;
}

export function DialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("mt-6 flex justify-end gap-2", className)} {...props} />;
}
