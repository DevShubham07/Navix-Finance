import * as React from "react";
import type { StaffRole } from "@/lib/domain";

// TODO: action bar showing the allowed maker-checker action for the current role
// (review / final approve / release) with confirm dialogs. Disables actions
// the role is not permitted to take to preserve separation of duties.
export interface MakerCheckerBarProps {
  role: StaffRole;
  onApprove?: () => void;
  onReject?: () => void;
  className?: string;
}

export function MakerCheckerBar({ role, onApprove, onReject, className }: MakerCheckerBarProps) {
  return (
    <div className={className} data-testid="maker-checker-bar">
      {/* TODO: render role-appropriate approve/reject controls */}
      <span>Actions for: {role}</span>
      <button type="button" onClick={onApprove}>Approve</button>
      <button type="button" onClick={onReject}>Reject</button>
    </div>
  );
}
