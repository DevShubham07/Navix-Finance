"use client";

import * as React from "react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "./dialog";

/**
 * A {@link Dialog} preset for one-click actions that should not be one click.
 *
 * Intended for the irreversible, outward-facing ones — a bulk borrower reminder (a real SMS +
 * email + in-app + WhatsApp send, with no cooldown behind it), a settlement approve/reject, a
 * verification override. Not for ordinary navigation or filtering, and worth resisting on
 * high-frequency single-row actions where it is pure friction.
 *
 * There is deliberately **no `tone="danger"`**: the console's button vocabulary is
 * `btn-outline` / `btn-navy` / `btn-gold` / `btn-ghost` and has no destructive style, so a
 * destructive confirm carries its warning in the title and body copy — which is how the existing
 * reject dialogs already read. Adding a red button is a design-system decision, not a side effect
 * of this primitive.
 *
 * `busy` keeps the dialog open and both buttons disabled while the mutation is in flight, so a
 * double-click cannot fire it twice — which is the real defence for the billable-provider and SMS
 * cases, rather more than the extra click is.
 */
export interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  body?: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  busy?: boolean;
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  body,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  busy = false,
}: ConfirmDialogProps) {
  const titleId = React.useId();
  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} size="md" aria-labelledby={titleId}>
      <DialogHeader>
        <DialogTitle id={titleId}>{title}</DialogTitle>
      </DialogHeader>
      {body && <div className="text-sm text-slate">{body}</div>}
      <DialogFooter>
        <button type="button" className="btn btn-sm btn-outline" onClick={onClose} disabled={busy}>
          {cancelLabel}
        </button>
        <button
          type="button"
          className="btn btn-sm btn-navy"
          onClick={onConfirm}
          disabled={busy}
        >
          {busy ? "Working…" : confirmLabel}
        </button>
      </DialogFooter>
    </Dialog>
  );
}
