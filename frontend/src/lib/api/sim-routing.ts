/**
 * Decides which backend a BFF request goes to. Only a staff session marked `sim` at login reaches the
 * simulation backend. Server-only: the SIM_* variables are never exposed to the browser.
 *
 * Fails closed: the two backends sign tokens with different secrets, so a token sent to the wrong one
 * (a forged cookie flag, or settings removed mid-session) is rejected with a 401.
 */

import type { NextRequest } from "next/server";
import { config } from "@/lib/config";

// Same literal as middleware.ts: importing bff-session here would pull next/headers into the proxy.
const STAFF_COOKIE = "navix_staff";

export interface BackendTarget {
  baseUrl: string;
  headers: Record<string, string>;
}

/** The sim backend, or null unless all three settings are present. */
export function simTarget(): BackendTarget | null {
  const email = process.env.SIM_LOGIN_EMAIL;
  const baseUrl = process.env.SIM_BACKEND_BASE_URL;
  const key = process.env.SIM_GATEWAY_KEY;
  if (!email || !baseUrl || !key) return null;
  return { baseUrl, headers: { "X-Sim-Gateway": key } };
}

export function isSimLogin(email: string): boolean {
  if (!simTarget()) return false;
  return email.trim().toLowerCase() === (process.env.SIM_LOGIN_EMAIL ?? "").trim().toLowerCase();
}

function production(): BackendTarget {
  return { baseUrl: config.backendBaseUrl, headers: {} };
}

export function targetForSession(session: { sim?: boolean } | null): BackendTarget {
  return (session?.sim === true ? simTarget() : null) ?? production();
}

/**
 * The target for a proxied call. Compares the token too: a browser can hold a sim staff session and a
 * real borrower session at once, and the borrower's calls must stay on production.
 */
export function targetForToken(req: NextRequest, token: string): BackendTarget {
  const raw = req.cookies.get(STAFF_COOKIE)?.value;
  if (!raw) return production();
  try {
    const staff = JSON.parse(raw) as { token?: string; sim?: boolean };
    return staff.token === token ? targetForSession(staff) : production();
  } catch {
    return production();
  }
}
