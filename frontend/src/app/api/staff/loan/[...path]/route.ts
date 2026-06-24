import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Staff loan proxy (read-only). Catch-all GET ->
 *   `${backendBaseUrl}/api/loan/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. Powers the loan
 * summary / outstanding view in the staff console.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

export async function GET(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/loan/${suffix}` : "/api/loan";

  return proxyToBackend(req, backendPath, {
    id: session.id,
    name: session.name,
    role: session.role,
  });
}
