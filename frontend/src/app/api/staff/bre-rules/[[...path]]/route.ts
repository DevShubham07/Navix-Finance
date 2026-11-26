import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/** BRE rule switches + thresholds + blocked pin code list (staff; ADMIN enforced by the backend). */
type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  const suffix = joinPath((await ctx.params).path);
  const backendPath = suffix ? `/api/staff/bre-rules/${suffix}` : "/api/staff/bre-rules";
  return proxyToBackend(req, backendPath, session.token);
}

export const GET = handle;
export const PUT = handle;
export const POST = handle;
export const DELETE = handle;
