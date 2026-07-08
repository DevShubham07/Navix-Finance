"use client";

/**
 * Shared hooks, constants and helpers for the staff live-pipeline building blocks.
 *
 * These are the low-level primitives every pipeline module (status queues, action
 * clusters, customer review, loan history, review lookup) shares: the live staff
 * session (`navix_staff` cookie), the post-action query invalidation, role labels,
 * and the permission/loan-status constants. Kept dependency-free (no imports from
 * other pipeline modules) so it sits at the root of the module graph.
 */

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type StaffRole, type Permission } from "@/lib/auth/rbac";
import { formatApiError } from "@/lib/api/errors";

/** Loan statuses that mean the loan is still live (vs. a past/closed loan). */
export const OPEN_LOAN_STATUSES = new Set(["ACTIVE", "OVERDUE", "IN_COLLECTIONS", "DISBURSED", "DEFAULTED"]);

// ---------------------------------------------------------------------------
// Live staff session (navix_staff cookie)
// ---------------------------------------------------------------------------

export interface StaffMe {
  id: string;
  name: string;
  role: StaffRole;
}

export async function fetchStaffMe(): Promise<StaffMe | null> {
  const res = await fetch("/api/auth/staff/me", { cache: "no-store", credentials: "same-origin" });
  if (!res.ok) return null;
  const json = (await res.json()) as { session: StaffMe | null };
  return json.session;
}

/** React Query wrapper for the live staff session. */
export function useStaffMe() {
  return useQuery({ queryKey: ["staff-me"], queryFn: fetchStaffMe });
}

export function errMessage(e: unknown): string {
  return formatApiError(e, "Action failed.");
}

export const ROLE_LABEL: Record<StaffRole, string> = {
  KYC_APPROVER: "KYC Approver",
  CREDIT_EXECUTIVE: "Credit Executive",
  CREDIT_HEAD: "Credit Head",
  DISBURSEMENT_HEAD: "Disbursement Head",
  ACCOUNTANT: "Accountant",
  COLLECTION_HEAD: "Collection Head",
  COLLECTION_EXECUTIVE: "Collection Executive",
  ADMIN: "Administrator",
  DEVELOPER: "Developer",
};

/** Roles that drive the application state machine. */
export const PIPELINE_ROLES: StaffRole[] = [
  "KYC_APPROVER",
  "CREDIT_HEAD",
  "CREDIT_EXECUTIVE",
  "DISBURSEMENT_HEAD",
  "ACCOUNTANT",
];

/**
 * Permissions that legitimately need to read customer PII (name, masked PAN/Aadhaar, salary,
 * employer, address, documents). Collection roles (only `collections:*`) and DEVELOPER (no perms)
 * are intentionally excluded — they have no need-to-know for a borrower's salary/employer.
 *
 * Shared by {@link CustomerReview} and {@link ReviewLookup}.
 */
export const REVIEW_PERMS: Permission[] = [
  "kyc:approve",
  "loan:review",
  "loan:approve",
  "loan:disburse",
  "loan:activate",
];

// ---------------------------------------------------------------------------
// Per-stage action refresh
// ---------------------------------------------------------------------------

/** Invalidate every queue + this app's events so the row leaves/updates. */
export function useRefreshAfterAction() {
  const qc = useQueryClient();
  return (id: number) => {
    qc.invalidateQueries({ queryKey: ["staff-queue"] });
    qc.invalidateQueries({ queryKey: ["staff-events", id] });
    // The unified detail page renders status + action buttons from this query —
    // without it they lag the stepper by a poll cycle and allow a stale re-click.
    qc.invalidateQueries({ queryKey: ["staff-application", id] });
    qc.invalidateQueries({ queryKey: ["staff-dashboard-stats"] });
    qc.invalidateQueries({ queryKey: ["staff-dashboard-queue"] });
  };
}
