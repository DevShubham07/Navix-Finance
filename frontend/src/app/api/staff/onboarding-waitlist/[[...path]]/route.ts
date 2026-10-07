import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/** Onboarding waitlist (staff, ADMIN enforced by the backend). `?q=` rides along via proxyToBackend. */
async function handle(req: NextRequest) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  return proxyToBackend(req, "/api/staff/onboarding-waitlist", session.token);
}

export const GET = handle;
