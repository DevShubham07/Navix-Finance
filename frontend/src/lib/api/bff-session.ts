/**
 * Server-side helpers for the BFF auth namespaces. SEPARATE staff and borrower
 * sessions: each lives in its own httpOnly cookie and is never shared.
 *
 *   - staff    -> cookie `navix_staff`    = { token, id, name, role }
 *   - borrower -> cookie `navix_borrower` = { token, id, customerId, name, mobile }
 *
 * The `token` is the backend-issued JWT. The proxy route handlers read it and
 * forward it as `Authorization: Bearer <token>`; it is never exposed to the
 * browser (the `/me` routes return the identity without the token).
 */

import { cookies } from "next/headers";
import type { StaffRole } from "@/lib/auth/rbac";

export const STAFF_COOKIE = "navix_staff";
export const BORROWER_COOKIE = "navix_borrower";

export interface StaffBffSession {
  /** Backend-issued JWT (forwarded as a bearer; never sent to the browser). */
  token: string;
  id: string;
  name: string;
  role: StaffRole;
}

export interface BorrowerBffSession {
  /** Backend-issued JWT (forwarded as a bearer; never sent to the browser). */
  token: string;
  id: string;
  customerId: number;
  name: string;
  mobile: string;
}

const COOKIE_OPTS = {
  httpOnly: true,
  path: "/",
  sameSite: "lax" as const,
  // Secure (HTTPS-only) in production; left off in dev so it works on http://localhost.
  secure: process.env.NODE_ENV === "production",
  maxAge: 60 * 60 * 24, // 1 day (staff)
};

// Borrowers get a 7-day "remember me" window (matches the backend borrower JWT TTL) so a returning,
// KYC-verified borrower skips the OTP for a week. Staff keep the shorter 1-day session.
const BORROWER_MAX_AGE = 60 * 60 * 24 * 7;

// ---------------------------------------------------------------------------
// Staff
// ---------------------------------------------------------------------------

export async function setStaffSession(session: StaffBffSession): Promise<void> {
  const store = await cookies();
  store.set(STAFF_COOKIE, JSON.stringify(session), COOKIE_OPTS);
}

export async function clearStaffSession(): Promise<void> {
  const store = await cookies();
  store.set(STAFF_COOKIE, "", { ...COOKIE_OPTS, maxAge: 0 });
}

export async function getStaffSession(): Promise<StaffBffSession | null> {
  const store = await cookies();
  const raw = store.get(STAFF_COOKIE)?.value;
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StaffBffSession;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Borrower
// ---------------------------------------------------------------------------

export async function setBorrowerSession(session: BorrowerBffSession): Promise<void> {
  const store = await cookies();
  store.set(BORROWER_COOKIE, JSON.stringify(session), { ...COOKIE_OPTS, maxAge: BORROWER_MAX_AGE });
}

export async function clearBorrowerSession(): Promise<void> {
  const store = await cookies();
  store.set(BORROWER_COOKIE, "", { ...COOKIE_OPTS, maxAge: 0 });
}

export async function getBorrowerSession(): Promise<BorrowerBffSession | null> {
  const store = await cookies();
  const raw = store.get(BORROWER_COOKIE)?.value;
  if (!raw) return null;
  try {
    return JSON.parse(raw) as BorrowerBffSession;
  } catch {
    return null;
  }
}
