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
  "COLLECTIONS_HEAD",
  "COLLECTION_OFFICER",
  "ADMIN",
] as const;

export type StaffRole = (typeof STAFF_ROLES)[number];

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
  | "staff:manage";

/** Static role -> permission mapping. TODO: confirm against backend authz. */
const ROLE_PERMISSIONS: Record<StaffRole, Permission[]> = {
  KYC_APPROVER: ["kyc:approve"],
  CREDIT_EXECUTIVE: ["loan:review"],
  CREDIT_HEAD: ["loan:approve"],
  DISBURSEMENT_HEAD: ["loan:disburse"],
  ACCOUNTANT: ["loan:activate"],
  COLLECTIONS_HEAD: ["collections:manage", "collections:interact"],
  COLLECTION_OFFICER: ["collections:interact"],
  ADMIN: [
    "kyc:approve",
    "loan:review",
    "loan:approve",
    "loan:disburse",
    "loan:activate",
    "collections:manage",
    "collections:interact",
    "staff:manage",
  ],
};

/** Returns true when the role grants the given permission. */
export function hasPermission(role: StaffRole, permission: Permission): boolean {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false;
}

/**
 * Separation-of-duties helper: throws if the actor performing a loan step
 * already performed a conflicting prior step on the same loan.
 *
 * TODO: implement against the loan's action history once wired to the backend.
 */
export function enforceSeparationOfDuties(_params: {
  loanId: string;
  step: "review" | "approve" | "disburse" | "activate";
  actorUserId: string;
}): void {
  // TODO: ensure reviewer != approver != disburser, and accountant is distinct.
}
