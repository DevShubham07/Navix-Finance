import { type Page } from "@playwright/test";

/** Staff roles (mirrors lib/auth/rbac.ts). */
export const STAFF_ROLES = [
  "KYC_APPROVER", "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT",
  "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "ADMIN", "DEVELOPER",
] as const;
export type StaffRole = (typeof STAFF_ROLES)[number];

/** Persona display names per role (from staff-personas.ts / V10 seed) — for UI clicks. */
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

/** Seeded login email per role (V10 seed); the shared demo password is below. */
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
export const STAFF_DEFAULT_PASSWORD = "Admin@12345";

/** Log a staff member in via the BFF (sets the httpOnly navix_staff cookie on the context). */
export async function loginStaff(page: Page, role: StaffRole): Promise<boolean> {
  const res = await page.request.post("/api/auth/staff/login", {
    data: { email: STAFF_PERSONA_EMAILS[role], password: STAFF_DEFAULT_PASSWORD },
  });
  return res.ok();
}

/** Request a borrower OTP and return the dev-echo code (backend runs with dev-echo on). */
export async function requestBorrowerOtp(page: Page, mobile: string): Promise<string> {
  const res = await page.request.post("/api/auth/borrower/otp/request", { data: { mobile } });
  const body = (await res.json()) as { devCode?: string };
  if (!body?.devCode) {
    throw new Error("No devCode returned — the backend must run with NAVIX_SMS_DEV_ECHO=true");
  }
  return body.devCode;
}

/** Log a borrower in via the BFF (OTP via dev-echo). Returns the applicantId. */
export async function loginBorrower(page: Page, mobile: string, name = "E2E Tester"): Promise<number> {
  const otp = await requestBorrowerOtp(page, mobile);
  const res = await page.request.post("/api/auth/borrower/login", { data: { mobile, otp, name } });
  const body = (await res.json()) as { applicantId: number };
  return body.applicantId;
}

/** A unique-ish 10-digit Indian mobile per test run (avoids OTP-store collisions). */
export function uniqueMobile(): string {
  const n = (Date.now() % 1_000_000).toString().padStart(6, "0");
  return "98" + n.slice(0, 8).padEnd(8, "0");
}
