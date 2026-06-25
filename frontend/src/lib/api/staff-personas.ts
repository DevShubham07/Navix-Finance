/**
 * Server-safe staff persona map (no "use client", no React) so BFF route
 * handlers can resolve a display name for a role. Mirrors STAFF_PERSONAS in
 * `src/lib/mock/session.ts`.
 */
import type { StaffRole } from "@/lib/auth/rbac";

export const STAFF_PERSONA_NAMES: Record<StaffRole, string> = {
  KYC_APPROVER: "Ananya Rao",
  CREDIT_EXECUTIVE: "Rahul Mehta",
  CREDIT_HEAD: "Priya Nair",
  DISBURSEMENT_HEAD: "Vikram Shah",
  ACCOUNTANT: "Deepa Iyer",
  COLLECTION_HEAD: "Arjun Patel",
  COLLECTION_EXECUTIVE: "Sana Khan",
  ADMIN: "Meera Krishnan",
  DEVELOPER: "Dev Ops",
};

const VALID_ROLES = new Set<string>(Object.keys(STAFF_PERSONA_NAMES));

export function isStaffRole(value: unknown): value is StaffRole {
  return typeof value === "string" && VALID_ROLES.has(value);
}
