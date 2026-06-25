/**
 * Server-side helpers for the BFF auth namespaces. SEPARATE staff and borrower
 * sessions: each lives in its own httpOnly cookie and is never shared.
 *
 *   - staff    -> cookie `navix_staff`    = { id, name, role }
 *   - borrower -> cookie `navix_borrower` = { id, applicantId, name, mobile }
 *
 * The proxy route handlers read these to inject the backend's demo identity
 * headers (X-Demo-Actor-Id / -Name / -Role).
 */

import { cookies } from "next/headers";
import type { StaffRole } from "@/lib/auth/rbac";

export const STAFF_COOKIE = "navix_staff";
export const BORROWER_COOKIE = "navix_borrower";

export interface StaffBffSession {
  id: string;
  name: string;
  role: StaffRole;
}

export interface BorrowerBffSession {
  id: string;
  applicantId: number;
  name: string;
  mobile: string;
}

const COOKIE_OPTS = {
  httpOnly: true,
  path: "/",
  sameSite: "lax" as const,
  // 1 day — demo sessions; not secure-only so it works on http://localhost.
  maxAge: 60 * 60 * 24,
};

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
  store.set(BORROWER_COOKIE, JSON.stringify(session), COOKIE_OPTS);
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
