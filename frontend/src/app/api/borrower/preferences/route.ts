import { type NextRequest } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Borrower notification-preferences proxy (Phase 2.2). GET/PUT →
 *   `${backendBaseUrl}/api/preferences`
 * injecting BORROWER identity from the `navix_borrower` cookie. The backend resolves the owner from
 * the JWT subject, so no application id is needed. 401 if no session.
 */
async function handle(req: NextRequest) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");
  return proxyToBackend(req, "/api/preferences", session.token);
}

export const GET = handle;
export const PUT = handle;
