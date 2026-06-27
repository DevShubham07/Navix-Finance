import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff invites proxy. Catch-all GET/POST ->
 *   `${backendBaseUrl}/api/staff/invites/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie.
 *
 * Note: `/accept` is also reachable from the public activate screen, but the
 * activate page already holds a staff session by the time it consumes a token.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/staff/invites/${suffix}` : "/api/staff/invites";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
