import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * ADMIN DSA administration proxy. Catch-all ->
 *   `${backendBaseUrl}/api/admin/dsa/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 *
 * `/api/admin/**` is audience-gated (any staff JWT, including a DSA's own), so `DsaAdminService`
 * enforces its own `requireAdmin()` on every method server-side — this proxy only forwards the
 * bearer, it is not the authz boundary.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/admin/dsa/${suffix}` : "/api/admin/dsa";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
