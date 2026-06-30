import { type NextRequest } from "next/server";
import { getStaffSession, getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Read-only feature-flags proxy -> `${backendBaseUrl}/api/feature-flags`.
 *
 *  - GET : the dev-controlled flag states ({ key: enabled }), so the UI can hide a disabled feature
 *          (e.g. the staff "Referral payouts" nav/page). Either session works; staff is preferred.
 *
 * There is intentionally NO PUT/POST — feature flags are changed only via SQL against the database.
 */

async function staffToken(): Promise<string | null> {
  const s = await getStaffSession();
  return s ? s.token : null;
}

async function borrowerToken(): Promise<string | null> {
  const s = await getBorrowerSession();
  return s ? s.token : null;
}

export async function GET(req: NextRequest) {
  const token = (await staffToken()) ?? (await borrowerToken());
  if (!token) return unauthorized("Authentication required.");
  return proxyToBackend(req, "/api/feature-flags", token);
}
