"use client";

import * as React from "react";
import { ShieldCheck, AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Role-aware maker-checker action bar. Renders the single decision the current
 * role is permitted to take on this item, with a notes field. When the role
 * isn't permitted (e.g. it already acted — separation of duties), the actions
 * are disabled with a reason.
 */
export interface MakerCheckerBarProps {
  /** Heading describing the pending decision, e.g. "Final credit approval". */
  title: string;
  approveLabel?: string;
  rejectLabel?: string;
  /** Notes captured with the decision. */
  onApprove?: (notes: string) => void;
  onReject?: (notes: string) => void;
  disabled?: boolean;
  /** Why the actions are disabled (shown when `disabled`). */
  disabledReason?: string;
  requireNotes?: boolean;
  className?: string;
}

export function MakerCheckerBar({
  title,
  approveLabel = "Approve",
  rejectLabel = "Reject",
  onApprove,
  onReject,
  disabled = false,
  disabledReason,
  requireNotes = false,
  className,
}: MakerCheckerBarProps) {
  const [notes, setNotes] = React.useState("");
  const notesMissing = requireNotes && notes.trim().length === 0;

  return (
    <div className={cn("rounded border border-line bg-white p-5 shadow-sm", className)} data-testid="maker-checker-bar">
      <div className="mb-3 flex items-center gap-2">
        <ShieldCheck size={18} className="text-navy" />
        <h4 className="font-serif text-base font-semibold text-navy">{title}</h4>
      </div>

      {disabled ? (
        <div className="flex items-start gap-2 rounded bg-warning-bg p-3 text-sm text-warning-700">
          <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
          <span>{disabledReason ?? "You are not permitted to take this action."}</span>
        </div>
      ) : (
        <>
          <div className="field mb-3">
            <label>Decision notes{requireNotes ? <span className="req"> *</span> : null}</label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Add a short justification for the audit trail…"
              rows={3}
            />
          </div>
          <div className="flex flex-wrap gap-3">
            {onApprove && (
              <Button variant="success" disabled={notesMissing} onClick={() => onApprove(notes)}>
                {approveLabel}
              </Button>
            )}
            {onReject && (
              <Button variant="destructive" disabled={notesMissing} onClick={() => onReject(notes)}>
                {rejectLabel}
              </Button>
            )}
          </div>
          {notesMissing && <p className="mt-2 text-xs text-muted">Notes are required for this decision.</p>}
        </>
      )}
    </div>
  );
}
