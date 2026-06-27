import { type NextRequest } from "next/server";
import { getStaffSession, getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized, forbidden, type ActorIdentity } from "@/lib/api/bff-proxy";

/**
 * Company payment block proxy -> `${backendBaseUrl}/api/payment-settings`.
 *
 *  - GET : read the payee (borrower repay screen + staff admin). Either session works; the staff
 *          session is preferred when both are present.
 *  - PUT : edit the payee — STAFF session required (the backend further restricts it to ADMIN).
 */

async function staffActor(): Promise<ActorIdentity | null> {
  const s = await getStaffSession();
  return s ? { id: s.id, name: s.name, role: s.role } : null;
}

async function borrowerActor(): Promise<ActorIdentity | null> {
  const s = await getBorrowerSession();
  return s ? { id: s.applicantId, name: s.name, role: "BORROWER" } : null;
}

export async function GET(req: NextRequest) {
  const actor = (await staffActor()) ?? (await borrowerActor());
  if (!actor) return unauthorized("Authentication required.");
  return proxyToBackend(req, "/api/payment-settings", actor);
}

export async function PUT(req: NextRequest) {
  const actor = await staffActor();
  if (!actor) return forbidden("Staff session required to edit payment settings.");
  return proxyToBackend(req, "/api/payment-settings", actor);
}
