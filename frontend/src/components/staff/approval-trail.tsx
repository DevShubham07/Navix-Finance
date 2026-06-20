import * as React from "react";
import type { ApprovalTrailEntry } from "@/lib/domain";

// TODO: render the maker-checker audit trail (who reviewed/approved/released, when, decision notes).
// Enforces separation of duties: Credit Executive != Credit Head != Disbursement Head.
export interface ApprovalTrailProps {
  entries: ApprovalTrailEntry[];
  className?: string;
}

export function ApprovalTrail({ entries, className }: ApprovalTrailProps) {
  return (
    <ul className={className} data-testid="approval-trail">
      {/* TODO: timeline of approval actions */}
      {entries.map((entry, index) => (
        <li key={index}>
          {entry.role} - {entry.action}
        </li>
      ))}
    </ul>
  );
}
