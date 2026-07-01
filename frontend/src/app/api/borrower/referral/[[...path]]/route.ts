import { type NextRequest } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Borrower referral proxy. Catch-all GET/POST ->
 *   `${backendBaseUrl}/api/referral/${path}${search}`
 * injecting BORROWER identity (actor id = customerId) from the `navix_borrower` cookie. 401 if no
 * session. SEPARATE from the staff proxy: only the borrower cookie is honoured here.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/referral/${suffix}` : "/api/referral";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
