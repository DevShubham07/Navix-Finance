import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Admin eSign-documents proxy. Catch-all ->
 *   `${backendBaseUrl}/api/admin/esign-documents/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. The backend enforces ADMIN-only + the flag.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/admin/esign-documents/${suffix}` : "/api/admin/esign-documents";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const POST = handle;
