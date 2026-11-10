import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/** Payment-gateway (PayU) on/off switch (staff, ADMIN enforced by the backend). */
async function handle(req: NextRequest) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  return proxyToBackend(req, "/api/staff/payment-gateway", session.token);
}

export const GET = handle;
export const PUT = handle;
