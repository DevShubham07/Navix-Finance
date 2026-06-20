import type { StaffRole } from "@/lib/auth/rbac";

export type { StaffRole };

export interface StaffUser {
  id: string;
  email: string;
  name: string;
  role: StaffRole;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface StaffInvite {
  id: string;
  email: string;
  role: StaffRole;
  invitedBy: string;
  status: "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED";
  expiresAt: string;
  createdAt: string;
}
