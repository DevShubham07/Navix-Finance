import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff customers proxy. Catch-all ->
 *   `${backendBaseUrl}/api/customers/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 *
 *  - GET : list/search customers, or one customer's full history.
 *  - PUT : ADMIN corrects a customer's KYC data (`{customerId}/profile`).
 *
 * SEPARATE from the borrower proxy: only the staff cookie is honoured here.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/customers/${suffix}` : "/api/customers";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const PUT = handle;
