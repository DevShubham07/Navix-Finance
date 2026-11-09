import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/** Close-applications switch (staff, ADMIN enforced by the backend). */
async function handle(req: NextRequest) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  return proxyToBackend(req, "/api/staff/applications-closed", session.token);
}

export const GET = handle;
export const PUT = handle;
