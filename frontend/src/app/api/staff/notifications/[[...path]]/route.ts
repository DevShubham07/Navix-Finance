import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff notifications proxy. Catch-all GET/POST ->
 *   `${backendBaseUrl}/api/notifications/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 *
 * The backend scopes the inbox to the caller (JWT audience/subject), so this and the
 * borrower proxy hit the SAME endpoint — only the cookie honoured here differs.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/notifications/${suffix}` : "/api/notifications";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
