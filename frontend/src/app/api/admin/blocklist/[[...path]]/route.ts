import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Fraud-blocklist admin proxy. Catch-all ->
 *   `${backendBaseUrl}/api/admin/blocklist/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie (admin-only at go-live).
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/admin/blocklist/${suffix}` : "/api/admin/blocklist";

  return proxyToBackend(req, backendPath, {
    id: session.id,
    name: session.name,
    role: session.role,
  });
}

export const GET = handle;
export const POST = handle;
export const DELETE = handle;
