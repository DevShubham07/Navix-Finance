"use client";

import * as React from "react";
import type { StaffRole } from "@/lib/auth/rbac";
import { readEnvelopeError, formatEnvelopeError } from "@/lib/api/errors";

/**
 * Client-side accessor for the REAL staff session.
 *
 * The session lives in the httpOnly `navix_staff` cookie (set by
 * `POST /api/auth/staff/login`, which authenticates against the backend and
 * stores the JWT server-side). The browser never sees the token — it reads the
 * sanitized identity from `GET /api/auth/staff/me` instead.
 */

export interface StaffSession {
  id: string;
  name: string;
  role: StaffRole;
}

const STAFF_SESSION_EVENT = "navix-staff-session";

/** Read the current staff identity from the BFF (or null when signed out). */
export async function fetchStaffSession(): Promise<StaffSession | null> {
  try {
    const res = await fetch("/api/auth/staff/me", { cache: "no-store", credentials: "same-origin" });
    if (!res.ok) return null;
    const json = (await res.json()) as { session: StaffSession | null };
    return json.session ?? null;
  } catch {
    return null;
  }
}

/**
 * Sign in with email + password (the real staff login). Authenticates against the
 * backend via the BFF and stores the JWT in the httpOnly `navix_staff` cookie.
 * Returns the sanitized session (no token); throws with the backend's message
 * (e.g. "Invalid email or password") on failure so the form can surface it.
 */
export async function loginStaff(email: string, password: string): Promise<StaffSession> {
  const res = await fetch("/api/auth/staff/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    throw new Error(formatEnvelopeError(await readEnvelopeError(res, "Sign-in failed. Please try again.")));
  }
  const session = (await res.json()) as StaffSession;
  if (typeof window !== "undefined") window.dispatchEvent(new Event(STAFF_SESSION_EVENT));
  return session;
}

/**
 * DEMO ONLY — one-click "act as role": maps the role to its seeded persona
 * server-side (the BFF role shortcut) to exercise the maker-checker chain. The
 * real login is {@link loginStaff} (email + password). Returns null on failure.
 */
export async function loginStaffAs(role: StaffRole): Promise<StaffSession | null> {
  const res = await fetch("/api/auth/staff/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ role }),
  });
  if (!res.ok) return null;
  const session = (await res.json()) as StaffSession;
  if (typeof window !== "undefined") window.dispatchEvent(new Event(STAFF_SESSION_EVENT));
  return session;
}

/** Clear the staff session cookie and notify any mounted `useStaffSession` hooks. */
export async function signOutStaff(): Promise<void> {
  try {
    await fetch("/api/auth/staff/logout", { method: "POST", credentials: "same-origin" });
  } catch {
    // best-effort — the cookie is httpOnly; logout is the only way to clear it
  }
  if (typeof window !== "undefined") window.dispatchEvent(new Event(STAFF_SESSION_EVENT));
}

/** Reactive staff session hook (re-reads on login/logout via the session event). */
export function useStaffSession(): { session: StaffSession | null; loading: boolean } {
  const [session, setSession] = React.useState<StaffSession | null>(null);
  const [loading, setLoading] = React.useState(true);
  React.useEffect(() => {
    let active = true;
    const sync = async () => {
      const s = await fetchStaffSession();
      if (!active) return;
      setSession(s);
      setLoading(false);
    };
    void sync();
    window.addEventListener(STAFF_SESSION_EVENT, sync);
    return () => {
      active = false;
      window.removeEventListener(STAFF_SESSION_EVENT, sync);
    };
  }, []);
  return { session, loading };
}
