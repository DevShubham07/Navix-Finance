import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Decision-history proxy (V45) -> `${backendBaseUrl}/api/staff/decisions${path}${search}`.
 *
 * Deliberately its own namespace rather than riding `/api/staff/users`: that one is the ADMIN
 * staff-administration surface, whereas every staffer may read their own decisions.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/staff/decisions/${suffix}` : "/api/staff/decisions";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
