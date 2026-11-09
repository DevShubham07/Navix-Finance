import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Admin customer-messaging proxy. Catch-all ->
 *   `${backendBaseUrl}/api/customer-messages/${path}${search}`
 * (templates, preview, send, log). ADMIN-only is enforced server-side; this only requires a staff
 * session. SEPARATE from the borrower proxy: only the staff cookie is honoured.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/customer-messages/${suffix}` : "/api/customer-messages";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
