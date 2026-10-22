import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Mandates & debits console proxy (read-only). GET ->
 *   `${backendBaseUrl}/api/mandates/${path}${search}`
 * The backend narrows it to COLLECTION_HEAD / ADMIN; every action goes through the existing
 * applications/collections proxies.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/mandates/${suffix}` : "/api/mandates";

  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
