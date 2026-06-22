"use client";

import * as React from "react";
import type { StaffRole } from "@/lib/auth/rbac";

/**
 * Client-side mock session for the clickable demo. Staff sessions set the
 * `navix_session` cookie that `middleware.ts` checks; borrower sessions are
 * kept in localStorage only (borrower routes aren't gated).
 *
 * Swap this whole module for real cookie/JWT auth when the backend is wired.
 */

const STAFF_COOKIE = "navix_session";
const STAFF_KEY = "navix.staff.session";
const BORROWER_KEY = "navix.borrower.session";

export interface StaffSession {
  kind: "staff";
  userId: string;
  name: string;
  email: string;
  role: StaffRole;
}

export interface BorrowerSession {
  kind: "borrower";
  userId: string;
  name: string;
  mobile: string;
}

/** Demo persona per staff role — one click to sign in as any role. */
export const STAFF_PERSONAS: Record<StaffRole, { name: string; email: string }> = {
  KYC_APPROVER: { name: "Ananya Rao", email: "ananya.rao@navix.finance" },
  CREDIT_EXECUTIVE: { name: "Rahul Mehta", email: "rahul.mehta@navix.finance" },
  CREDIT_HEAD: { name: "Priya Nair", email: "priya.nair@navix.finance" },
  DISBURSEMENT_HEAD: { name: "Vikram Shah", email: "vikram.shah@navix.finance" },
  ACCOUNTANT: { name: "Deepa Iyer", email: "deepa.iyer@navix.finance" },
  COLLECTIONS_HEAD: { name: "Arjun Patel", email: "arjun.patel@navix.finance" },
  COLLECTION_OFFICER: { name: "Sana Khan", email: "sana.khan@navix.finance" },
  ADMIN: { name: "Meera Krishnan", email: "meera.krishnan@navix.finance" },
};

export const STAFF_ROLE_LABELS: Record<StaffRole, string> = {
  KYC_APPROVER: "KYC Approver",
  CREDIT_EXECUTIVE: "Credit Executive",
  CREDIT_HEAD: "Credit Head",
  DISBURSEMENT_HEAD: "Disbursement Head",
  ACCOUNTANT: "Accountant",
  COLLECTIONS_HEAD: "Collections Head",
  COLLECTION_OFFICER: "Collection Officer",
  ADMIN: "Administrator",
};

function setCookie(name: string, value: string, days = 1) {
  if (typeof document === "undefined") return;
  const expires = new Date(Date.now() + days * 864e5).toUTCString();
  document.cookie = `${name}=${encodeURIComponent(value)};expires=${expires};path=/`;
}

function clearCookie(name: string) {
  if (typeof document === "undefined") return;
  document.cookie = `${name}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/`;
}

export function signInStaff(role: StaffRole): StaffSession {
  const persona = STAFF_PERSONAS[role];
  const session: StaffSession = {
    kind: "staff",
    userId: `staff-${role.toLowerCase()}`,
    name: persona.name,
    email: persona.email,
    role,
  };
  localStorage.setItem(STAFF_KEY, JSON.stringify(session));
  setCookie(STAFF_COOKIE, session.userId);
  window.dispatchEvent(new Event("navix-session"));
  return session;
}

export function signOutStaff() {
  localStorage.removeItem(STAFF_KEY);
  clearCookie(STAFF_COOKIE);
  window.dispatchEvent(new Event("navix-session"));
}

export function getStaffSession(): StaffSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(STAFF_KEY);
    return raw ? (JSON.parse(raw) as StaffSession) : null;
  } catch {
    return null;
  }
}

export function signInBorrower(name = "Aarav Sharma", mobile = "98765 43210"): BorrowerSession {
  const session: BorrowerSession = { kind: "borrower", userId: "borrower-demo", name, mobile };
  localStorage.setItem(BORROWER_KEY, JSON.stringify(session));
  window.dispatchEvent(new Event("navix-session"));
  return session;
}

export function signOutBorrower() {
  localStorage.removeItem(BORROWER_KEY);
  window.dispatchEvent(new Event("navix-session"));
}

export function getBorrowerSession(): BorrowerSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(BORROWER_KEY);
    return raw ? (JSON.parse(raw) as BorrowerSession) : null;
  } catch {
    return null;
  }
}

/** Reactive staff session hook (re-reads on the `navix-session` event). */
export function useStaffSession(): { session: StaffSession | null; loading: boolean } {
  const [session, setSession] = React.useState<StaffSession | null>(null);
  const [loading, setLoading] = React.useState(true);
  React.useEffect(() => {
    const sync = () => setSession(getStaffSession());
    sync();
    setLoading(false);
    window.addEventListener("navix-session", sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener("navix-session", sync);
      window.removeEventListener("storage", sync);
    };
  }, []);
  return { session, loading };
}

/** Reactive borrower session hook. */
export function useBorrowerSession(): { session: BorrowerSession | null; loading: boolean } {
  const [session, setSession] = React.useState<BorrowerSession | null>(null);
  const [loading, setLoading] = React.useState(true);
  React.useEffect(() => {
    const sync = () => setSession(getBorrowerSession());
    sync();
    setLoading(false);
    window.addEventListener("navix-session", sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener("navix-session", sync);
      window.removeEventListener("storage", sync);
    };
  }, []);
  return { session, loading };
}
