import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Collections proxy. Catch-all GET/POST ->
 *   `${backendBaseUrl}/api/collections/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie (settlement SoD reads it).
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/collections/${suffix}` : "/api/collections";

  return proxyToBackend(req, backendPath, {
    id: session.id,
    name: session.name,
    role: session.role,
  });
}

export const GET = handle;
export const POST = handle;
