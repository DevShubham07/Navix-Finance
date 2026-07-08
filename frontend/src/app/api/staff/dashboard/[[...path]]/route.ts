import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff dashboard proxy. Catch-all GET ->
 *   `${backendBaseUrl}/api/dashboard/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/dashboard/${suffix}` : "/api/dashboard";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
