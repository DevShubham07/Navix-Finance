/**
 * Server-safe staff persona map (no "use client", no React) so BFF route
 * handlers and the staff login page can resolve a role's display name + the
 * seeded login email.
 *
 * The "pick a role" login UX maps the chosen role to its seeded staff account
 * (email below + the shared demo password) and authenticates against the real
 * backend `POST /api/auth/staff/login`.
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

/** Seeded login email per role (matches the backend demo staff seed). */
export const STAFF_PERSONA_EMAILS: Record<StaffRole, string> = {
  KYC_APPROVER: "ananya.rao@navix.example",
  CREDIT_EXECUTIVE: "rahul.mehta@navix.example",
  CREDIT_HEAD: "priya.nair@navix.example",
  DISBURSEMENT_HEAD: "vikram.shah@navix.example",
  ACCOUNTANT: "deepa.iyer@navix.example",
  COLLECTION_HEAD: "arjun.patel@navix.example",
  COLLECTION_EXECUTIVE: "sana.khan@navix.example",
  ADMIN: "meera.krishnan@navix.example",
  DEVELOPER: "dev.ops@navix.example",
};

/** Shared demo password for every seeded staff account. */
export const STAFF_DEFAULT_PASSWORD = "Admin@12345";

const VALID_ROLES = new Set<string>(Object.keys(STAFF_PERSONA_NAMES));

export function isStaffRole(value: unknown): value is StaffRole {
  return typeof value === "string" && VALID_ROLES.has(value);
}
