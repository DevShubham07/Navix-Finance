import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

type Ctx = { params: Promise<{ path?: string[] }> };
async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  const { path } = await ctx.params;
  const suffix = joinPath(path);
  return proxyToBackend(req, suffix ? `/api/admin/provider-apis/${suffix}` : "/api/admin/provider-apis", session.token);
}
export const GET = handle;
export const POST = handle;
