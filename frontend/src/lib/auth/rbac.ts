/**
 * Role-based access control for NAVIX staff users.
 *
 * StaffRole mirrors the backend (com.navix.iam) role enum exactly.
 *
 * Separation of duties (maker-checker): the same person must not perform
 * conflicting steps of a loan. Specifically CREDIT_EXECUTIVE (review) must
 * differ from CREDIT_HEAD (final approve) must differ from DISBURSEMENT_HEAD
 * (release); the ACCOUNTANT independently confirms the bank transfer to
 * activate the loan. See {@link enforceSeparationOfDuties}.
 */

export const STAFF_ROLES = [
  "KYC_APPROVER",
  "CREDIT_EXECUTIVE",
  "CREDIT_HEAD",
  "DISBURSEMENT_HEAD",
  "ACCOUNTANT",
  "COLLECTION_HEAD",
  "COLLECTION_EXECUTIVE",
  "ADMIN",
  "DEVELOPER",
] as const;

export type StaffRole = (typeof STAFF_ROLES)[number];

/** Human-readable label per staff role (mirrors the backend role names). */
export const STAFF_ROLE_LABELS: Record<StaffRole, string> = {
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

/**
 * Fine-grained permissions checked by the UI and BFF.
 * TODO: expand as feature surfaces are built out.
 */
export type Permission =
  | "kyc:approve"
  | "loan:review"
  | "loan:approve"
  | "loan:disburse"
  | "loan:activate"
  | "collections:manage"
  | "collections:interact"
  | "staff:manage"
  // Customers pane: every staff role may view the borrower-centric roll-up (product decision —
  // all staff see customer details incl. PII); only ADMIN may edit it / take lifecycle actions.
  | "customer:view"
  | "customer:manage";

/** Static role -> permission mapping. TODO: confirm against backend authz. */
const ROLE_PERMISSIONS: Record<StaffRole, Permission[]> = {
  KYC_APPROVER: ["kyc:approve", "customer:view"],
  CREDIT_EXECUTIVE: ["loan:review", "customer:view"],
  CREDIT_HEAD: ["loan:approve", "customer:view"],
  DISBURSEMENT_HEAD: ["loan:disburse", "customer:view"],
  ACCOUNTANT: ["loan:activate", "customer:view"],
  COLLECTION_HEAD: ["collections:manage", "collections:interact", "customer:view"],
  COLLECTION_EXECUTIVE: ["collections:interact", "customer:view"],
  DEVELOPER: ["customer:view"],
  ADMIN: [
    "kyc:approve",
    "loan:review",
    "loan:approve",
    "loan:disburse",
    "loan:activate",
    "collections:manage",
    "collections:interact",
    "staff:manage",
    "customer:view",
    "customer:manage",
  ],
};

/** Returns true when the role grants the given permission. */
export function hasPermission(role: StaffRole, permission: Permission): boolean {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false;
}

/** A maker-checker step in the loan lifecycle. */
export type LoanStep = "kyc" | "review" | "approve" | "disburse" | "activate";

/** The permission each step requires. */
export const STEP_PERMISSION: Record<LoanStep, Permission> = {
  kyc: "kyc:approve",
  review: "loan:review",
  approve: "loan:approve",
  disburse: "loan:disburse",
  activate: "loan:activate",
};

const STEP_LABEL: Record<LoanStep, string> = {
  kyc: "KYC clearance",
  review: "credit review",
  approve: "credit approval",
  disburse: "disbursement release",
  activate: "transfer confirmation",
};

export interface SoDResult {
  allowed: boolean;
  reason?: string;
}

/** Minimal shape of a trail entry needed to evaluate separation of duties. */
export interface SoDTrailEntry {
  actorId: string;
}

/**
 * Evaluate separation of duties for a maker-checker step.
 *
 * Two independent gates:
 *  1. **Authorisation** — the actor's role must hold the step's permission.
 *  2. **Separation** — the same person must not act twice on the same loan.
 *     Because each step needs a distinct permission AND the same actor is
 *     blocked once they appear in the trail, this enforces
 *     reviewer ≠ approver ≠ disburser, with the accountant independent — even
 *     for an ADMIN who technically holds every permission.
 *
 * Pure and side-effect free so the UI can disable controls with a reason and
 * the store can guard defensively against the same result.
 */
export function evaluateSoD(params: {
  step: LoanStep;
  role: StaffRole;
  actorId: string;
  trail: SoDTrailEntry[];
}): SoDResult {
  const { step, role, actorId, trail } = params;

  if (!hasPermission(role, STEP_PERMISSION[step])) {
    return { allowed: false, reason: `Your role isn't authorised for ${STEP_LABEL[step]}.` };
  }

  if (trail.some((t) => t.actorId === actorId)) {
    return {
      allowed: false,
      reason: `Separation of duties: you've already acted on this application. A different officer must complete the ${STEP_LABEL[step]}.`,
    };
  }

  return { allowed: true };
}

/**
 * Throwing wrapper around {@link evaluateSoD} for call sites that prefer to
 * fail hard (mirrors the backend authz behaviour in com.navix.iam).
 */
export function enforceSeparationOfDuties(params: {
  step: LoanStep;
  role: StaffRole;
  actorId: string;
  trail: SoDTrailEntry[];
}): void {
  const result = evaluateSoD(params);
  if (!result.allowed) throw new Error(result.reason);
}
