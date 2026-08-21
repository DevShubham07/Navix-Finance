import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff loans register proxy. Catch-all ->
 *   `${backendBaseUrl}/api/loans/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 *
 *  - GET : the loan register (read-only — this surface has no write path).
 *
 * Optional catch-all (`[[...path]]`), NOT a required one (`[...path]`) — a required catch-all does
 * not match the bare `/api/staff/loans` path, and that bare path IS the list route here. That exact
 * bug already bit `/api/staff/customers` once (see its route file); don't reintroduce it.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/loans/${suffix}` : "/api/loans";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
