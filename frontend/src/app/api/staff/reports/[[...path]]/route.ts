import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized, forbidden } from "@/lib/api/bff-proxy";

/**
 * Admin reports proxy. Catch-all GET ->
 *   `${backendBaseUrl}/api/reports/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session; the backend allows ADMIN only.
 * POST is allowed only for the customer-data export (`customer-export/match` and `customer-export/rows`);
 * every other report is read-only.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  if (req.method === "POST" && !(path?.length === 2 && path[0] === "customer-export" && ["match", "rows"].includes(path[1]))) {
    return forbidden("Only the customer export accepts POST.");
  }
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/reports/${suffix}` : "/api/reports";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
