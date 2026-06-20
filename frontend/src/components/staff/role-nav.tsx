import * as React from "react";
import type { StaffRole } from "@/lib/domain";

// TODO: render role-scoped navigation links for staff portals
// (Credit Executive, Credit Head, Disbursement Head, Accountant, Collections).
export interface RoleNavProps {
  role: StaffRole;
  className?: string;
}

export function RoleNav({ role, className }: RoleNavProps) {
  return (
    <nav className={className} data-testid="role-nav">
      {/* TODO: build per-role menu items */}
      <span>Navigation for: {role}</span>
    </nav>
  );
}
