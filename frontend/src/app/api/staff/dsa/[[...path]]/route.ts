import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * DSA portal proxy. Catch-all ->
 *   `${backendBaseUrl}/api/dsa/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 *
 * Role narrowing (DSA-only) happens server-side in `DsaService` off the JWT — this proxy only
 * forwards the bearer, exactly like the other staff proxies.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/dsa/${suffix}` : "/api/dsa";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const PUT = handle;
export const POST = handle;
