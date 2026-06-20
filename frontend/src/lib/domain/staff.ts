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

/**
 * Maker-checker actions recorded on an application/loan as it moves through
 * review, approval, and disbursement. Used to enforce and audit separation
 * of duties (recommender != approver != disburser).
 */
export type ApprovalAction =
  | "RECOMMEND"
  | "APPROVE"
  | "REJECT"
  | "RELEASE"
  | "DISBURSE";

/** A single entry in the maker-checker audit trail. */
export interface ApprovalTrailEntry {
  id: string;
  /** Staff member who took the action. */
  actorId: string;
  actorName: string;
  /** Role under which the action was taken. */
  role: StaffRole;
  action: ApprovalAction;
  /** Optional decision notes captured at the time of the action. */
  notes?: string;
  /** ISO-8601 timestamp of when the action was taken. */
  createdAt: string;
}
