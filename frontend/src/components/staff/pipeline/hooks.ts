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
 * Permissions that grant read access to a customer's PII (name, masked PAN/Aadhaar, salary,
 * employer, address, documents). Includes `customer:view` — the product decision that EVERY
 * staff role may view customer PII (mirrors the Customers pane / `CustomerDetailDialog`, which
 * every role can open); only `customer:manage` (ADMIN) may edit it. The credit/KYC maker-checker
 * permissions are also listed so a reviewer's access doesn't depend on `customer:view` alone.
 *
 * `.some(p => hasPermission(role, p))` against this list is therefore true for every staff role —
 * the check exists so a future role without `customer:view` is still handled correctly, not to
 * exclude anyone today. Collections/DEVELOPER were previously excluded here by mistake (this list
 * held only the credit/KYC permissions), which silently hid `ReviewLookup` for those roles and
 * made every "Open" popup dead-end on "Customer details aren't available to your role" — fixed by
 * adding `customer:view`.
 *
 * Shared by {@link CustomerReview}, {@link ReviewLookup}, {@link ApplicationJourney} and
 * {@link ApplicationDetailDialog}.
 */
export const REVIEW_PERMS: Permission[] = [
  "customer:view",
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
