import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Bureau KBA outreach proxy. Catch-all ->
 *   `${backendBaseUrl}/api/admin/bureau-challenge/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie.
 *
 * Exists so the Customers page's failure dialog can chase one borrower whose credit report is
 * sitting behind an unanswered bureau security question. The backend enforces ADMIN itself; this
 * only carries the session. The cohort endpoints (`/preview`, `/notify`) remain reachable here too
 * and are still curl-driven — there is deliberately no screen that sends to 200 people at once.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix
    ? `/api/admin/bureau-challenge/${suffix}`
    : "/api/admin/bureau-challenge";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
