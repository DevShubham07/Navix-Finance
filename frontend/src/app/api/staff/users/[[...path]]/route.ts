import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff-user administration proxy. Catch-all ->
 *   `${backendBaseUrl}/api/staff/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. The `users` namespace
 * keeps this distinct from the `/api/staff/applications` + `/api/staff/loan` proxies.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/staff/${suffix}` : "/api/staff";

  return proxyToBackend(req, backendPath, {
    id: session.id,
    name: session.name,
    role: session.role,
  });
}

export const GET = handle;
export const PUT = handle;
export const DELETE = handle;
