import { type BrowserContext, type Page } from "@playwright/test";

/** Staff roles (mirrors lib/auth/rbac.ts). */
export const STAFF_ROLES = [
  "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT",
  "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "ADMIN", "DEVELOPER",
] as const;
export type StaffRole = (typeof STAFF_ROLES)[number];

/** Persona display names per role (from staff-personas.ts / V10 seed) — for UI clicks. */
export const STAFF_PERSONA_NAMES: Record<StaffRole, string> = {
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

/**
 * One real sign-in per role per worker; every later call replays the cookie.
 *
 * <p>The backend rate-limits `/api/auth/staff/login` to a handful of attempts in a short window and
 * counts SUCCESSES as well as failures (`AttemptLimiter`), so a suite that logs in on every test
 * locks its own accounts out part-way through and fails with what look like UI errors — a queue
 * heading "not found" because the page under test is really the login screen. Caching keeps each
 * account at exactly one attempt, which is well inside any cap and cuts a couple of seconds per test.
 *
 * <p>Safe to replay: the staff JWT lives a day and `/api/auth/staff/logout` only clears the cookie —
 * there is no server-side revocation to invalidate a cached one.
 */
const staffCookieCache = new Map<StaffRole, Awaited<ReturnType<BrowserContext["cookies"]>>>();

export async function loginStaff(page: Page, role: StaffRole): Promise<boolean> {
  const cached = staffCookieCache.get(role);
  if (cached?.length) {
    await page.context().addCookies(cached);
    return true;
  }
  const res = await page.request.post("/api/auth/staff/login", {
    data: { email: STAFF_PERSONA_EMAILS[role], password: STAFF_DEFAULT_PASSWORD },
  });
  if (!res.ok()) return false;
  const session = (await page.context().cookies()).filter((c) => c.name === "navix_staff");
  if (session.length) staffCookieCache.set(role, session);
  return true;
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
