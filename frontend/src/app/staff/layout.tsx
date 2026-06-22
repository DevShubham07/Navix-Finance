import type { ReactNode } from "react";
import { StaffShell } from "@/components/staff/staff-shell";

/**
 * Staff console shell. Renders the role-aware sidebar + topbar for the
 * back-office maker-checker tools; the login/activation pages render bare
 * (handled inside StaffShell). Route-level auth is gated by middleware.ts.
 */
export default function StaffLayout({ children }: { children: ReactNode }) {
  return <StaffShell>{children}</StaffShell>;
}
