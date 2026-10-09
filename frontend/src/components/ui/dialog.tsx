"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/utils";
import { useFocusTrap } from "@/hooks/use-focus-trap";

/**
 * `md` — confirmations and short forms. `lg` — detail panels. `xl` — the wide multi-tab consoles
 * (the unified application dialog). Omit for the design's default `.modal` width.
 */
export type DialogSize = "md" | "lg" | "xl";

const sizeClasses: Record<DialogSize, string> = {
  md: "!max-w-[460px]",
  lg: "!max-w-[56rem]",
  xl: "!max-w-[1400px]",
};

export interface DialogProps {
  open: boolean;
  onClose: () => void;
  children?: React.ReactNode;
  className?: string;
  size?: DialogSize;
  /** Accessible name for the dialog (use one of the two). */
  "aria-label"?: string;
  "aria-labelledby"?: string;
}

/**
 * Lightweight modal using the design's `.modal-overlay` / `.modal` styling.
 * Used for confirm actions such as maker-checker approvals and disbursement
 * release. Closes on overlay click and Escape.
 *
 * Enforces a **focus trap** (Tab/Shift+Tab wrap) and **focus restore** to the trigger on close via
 * {@link useFocusTrap}, and **locks body scroll** while open — both of which {@link Drawer} already
 * had and this did not, so a keyboard user could tab out of an open modal into the page behind it
 * and a wheel scroll moved the register underneath. `size` replaces the `!max-w-*` overrides that
 * call sites were each inventing.
 *
 * Rendered through a portal to `document.body`. Call sites legitimately mount a dialog deep inside
 * the tree — the staff queue rows open theirs from inside a `position: sticky` table cell, which
 * creates a stacking context that trapped the overlay *below* the sticky table header (the navy
 * header bars painted straight over the open modal). A portal takes the overlay out of every
 * ancestor's stacking context, so its `z-index: 200` means what it says.
 */
export function Dialog({
  open,
  onClose,
  children,
  className,
  size,
  "aria-label": ariaLabel,
  "aria-labelledby": ariaLabelledby,
}: DialogProps) {
  // Portals need a DOM target, which doesn't exist during SSR / the first render pass.
  const [mounted, setMounted] = React.useState(false);
  React.useEffect(() => setMounted(true), []);
  const panelRef = useFocusTrap<HTMLDivElement>(open && mounted);

  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Lock body scroll while open, so a wheel scroll does not move the register behind the modal.
  React.useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  if (!open || !mounted) return null;
  return createPortal(
    <div className="modal-overlay show" onClick={onClose}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledby}
        tabIndex={-1}
        className={cn("modal outline-none", size && sizeClasses[size], className)}
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
