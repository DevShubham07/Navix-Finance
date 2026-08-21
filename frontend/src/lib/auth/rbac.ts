/**
 * Role-based access control for DhanBoost staff users.
 *
 * StaffRole mirrors the backend (com.navix.iam) role enum exactly.
 *
 * Credit Head and the assigned Credit Executive share the single credit-decision
 * stage; assignment controls Executive ownership. The Disbursement Head separately
 * owns release, and the Accountant verifies borrower repayments.
 */

export const STAFF_ROLES = [
  "CREDIT_EXECUTIVE",
  "CREDIT_HEAD",
  "DISBURSEMENT_HEAD",
  "ACCOUNTANT",
  "COLLECTION_HEAD",
  "COLLECTION_EXECUTIVE",
  "TELECALLER",
  "DSA",
  "ADMIN",
  "DEVELOPER",
] as const;

export type StaffRole = (typeof STAFF_ROLES)[number];

/** Human-readable label per staff role (mirrors the backend role names). */
export const STAFF_ROLE_LABELS: Record<StaffRole, string> = {
  CREDIT_EXECUTIVE: "Credit Executive",
  CREDIT_HEAD: "Credit Head",
  DISBURSEMENT_HEAD: "Disbursement Head",
  ACCOUNTANT: "Accountant",
  COLLECTION_HEAD: "Collection Head",
  COLLECTION_EXECUTIVE: "Collection Executive",
  TELECALLER: "Telecaller",
  DSA: "DSA",
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
  // The loan pipeline workbench ("Live applications"). Held by every role that has a stage in the
  // lifecycle — i.e. everyone except TELECALLER, whose job stops at calling and logging.
  | "loan:pipeline"
  // Customers pane. customer:view grants the tab; what it CONTAINS depends on customer:view:all —
  // department Heads + ADMIN see the whole book, everyone else is scoped server-side to customers
  // assigned to them or that they've recorded a decision on. This token mirrors the backend
  // CustomerService.FULL_CUSTOMER_VIEW_ROLES set; the real enforcement is there, not here (the UI
  // check only drives copy — never trust it for access).
  // Only ADMIN may edit / take lifecycle actions. customer:assign is Heads + TELECALLER (+ ADMIN) —
  // allocate book of business without granting KYC-edit/delete.
  | "customer:view"
  | "customer:view:all"
  | "customer:manage"
  | "customer:assign"
  // Attach a document to an application. Held by ADMIN and the two credit roles: a reviewer often
  // has to upload a payslip or bank statement the borrower sent in out-of-band, and routing that
  // through an ADMIN stalled files. Strictly ADD — deleting/replacing a borrower-submitted document
  // remains customer:manage (ADMIN). Mirrored backend-side by
  // CustomerReviewService.DOCUMENT_UPLOAD_ROLES; the real enforcement is there, not here.
  | "document:upload"
  // Telecaller lead intake + disposition; ADMIN shares write + owns the tracker dashboard.
  | "leads:manage"
  // Referral payouts: the Disbursement Head settles the ₹-rewards (logs a txn id) and sees the
  // referral-expense dashboard; ADMIN has oversight.
  | "referral:payout"
  // Re-fire a provider verification (PAN / email / address / bureau / employment / penny-drop) from
  // the staff console. Held by ADMIN and the credit roles: they already hold kyc:approve, so without
  // this they could OVERRIDE a stale provider result but not simply re-run it, which is backwards.
  // Note it is per-action, not per-check — a credit reviewer who can re-run the employment lookup can
  // also re-run the (billable) bureau pull.
  | "verification:retry"
  // The DSA self-service portal (own leads, outreach, own commissions/earnings). DSA gets this and
  // NOTHING else — explicitly not customer:view, not leads:manage, not loan:pipeline: a DSA must
  // never see another agent's leads, telecaller leads, or any KYC/customer data.
  | "dsa:portal"
  // ADMIN administration of the DSA program (roster, company-wide lead register, commission ledger
  // pay/void/reassign, outreach audit).
  | "dsa:manage"
  // The company-wide Loans register (every disbursed loan, sortable/filterable, read-only). ADMIN
  // for oversight; COLLECTION_HEAD because DPD/overdue triage is their job and the register is
  // where they'd start a case. This token is display-only wording — the backend enforces the real
  // gate (mirrors this file's own convention: see customer:view / document:upload above).
  | "loan:register";

/** Static role -> permission mapping. TODO: confirm against backend authz. */
const ROLE_PERMISSIONS: Record<StaffRole, Permission[]> = {
  // The credit roles absorbed the deleted KYC_APPROVER (V45). The Executive holds kyc:approve
  // because the sanction IS the credit decision — there is no Head counter-approval; the Head's
  // loan:approve now gates assignment (handing work out), not a second sign-off.
  CREDIT_EXECUTIVE: [
    "kyc:approve",
    "loan:review",
    "customer:view",
    "loan:pipeline",
    "document:upload",
    "verification:retry",
  ],
  CREDIT_HEAD: [
    "kyc:approve",
    "loan:review",
    "loan:approve",
    "verification:retry",
    "customer:view",
    "customer:view:all",
    "customer:assign",
    "loan:pipeline",
    "document:upload",
  ],
  DISBURSEMENT_HEAD: [
    "loan:disburse",
    "customer:view",
    "customer:view:all",
    "referral:payout",
    "loan:pipeline",
  ],
  ACCOUNTANT: ["loan:activate", "customer:view", "loan:pipeline"],
  COLLECTION_HEAD: [
    "collections:manage",
    "collections:interact",
    "customer:view",
    "customer:view:all",
    "customer:assign",
    "loan:pipeline",
    "loan:register",
  ],
  COLLECTION_EXECUTIVE: ["collections:interact", "customer:view", "loan:pipeline"],
  // Telecaller: view customers, enter DSA-style leads, disposition calls, self-assign chase-up
  // work off the telecalling queue. No lifecycle permission.
  TELECALLER: ["customer:view", "leads:manage", "customer:assign"],
  // External commission agent, firewalled from the platform: own leads + own commissions/earnings
  // only. Deliberately NOT customer:view, NOT leads:manage, NOT loan:pipeline.
  DSA: ["dsa:portal"],
  DEVELOPER: ["customer:view", "loan:pipeline"],
  ADMIN: [
    "kyc:approve",
    "loan:review",
    "loan:approve",
    "loan:disburse",
    "loan:activate",
    "loan:pipeline",
    "collections:manage",
    "collections:interact",
    "staff:manage",
    "customer:view",
    "customer:view:all",
    "customer:manage",
    "customer:assign",
    "document:upload",
    "leads:manage",
    "referral:payout",
    "verification:retry",
    "loan:register",
    // NOT dsa:portal — that is the DSA's own self-service portal, gated backend-side by
    // DsaService.requireDsaId() with a strict role equality and no ADMIN bypass (the portal is
    // scoped by JWT identity, and an ADMIN owns no lead/commission rows). Granting it here put
    // "My leads"/"My earnings" in the ADMIN nav, where every call failed FORBIDDEN_ROLE
    // "DSA required". ADMIN oversight goes through dsa:manage → /staff/admin/dsa.
    "dsa:manage",
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
