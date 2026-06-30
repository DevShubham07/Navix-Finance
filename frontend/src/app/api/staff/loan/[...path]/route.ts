import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized, forbidden } from "@/lib/api/bff-proxy";

/**
 * Staff loan proxy. Catch-all ->
 *   `${backendBaseUrl}/api/loan/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie.
 *
 *  - GET  : loan summary / outstanding / the pending-repayments queue.
 *  - POST : record a repayment, or verify/reject one
 *           (`{loanId}/repayments/{paymentId}/{verify|reject}`) — the accountant's maker-checker step.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

export async function GET(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/loan/${suffix}` : "/api/loan";

  return proxyToBackend(req, backendPath, session.token);
}

export async function POST(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  // Record a repayment or verify/reject one; nothing else under /api/loan is a POST.
  if (!/^\d+\/repayments(\/\d+\/(verify|reject))?$/.test(suffix)) {
    return forbidden("Unsupported loan action.");
  }

  return proxyToBackend(req, `/api/loan/${suffix}`, session.token);
}
