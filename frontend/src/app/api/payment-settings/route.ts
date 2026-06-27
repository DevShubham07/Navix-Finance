import { type NextRequest } from "next/server";
import { getStaffSession, getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized, forbidden } from "@/lib/api/bff-proxy";

/**
 * Company payment block proxy -> `${backendBaseUrl}/api/payment-settings`.
 *
 *  - GET : read the payee (borrower repay screen + staff admin). Either session works; the staff
 *          session is preferred when both are present.
 *  - PUT : edit the payee — STAFF session required (the backend further restricts it to ADMIN).
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
  return proxyToBackend(req, "/api/payment-settings", token);
}

export async function PUT(req: NextRequest) {
  const token = await staffToken();
  if (!token) return forbidden("Staff session required to edit payment settings.");
  return proxyToBackend(req, "/api/payment-settings", token);
}
