import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Skip Tracer proxy — `GET`/`POST /api/staff/skip-trace/customers/{id}`. Who may run (COLLECTION_HEAD /
 * ADMIN) versus read (any staff but DSA) is the backend's decision, in `SkipTraceService`.
 */
type Ctx = { params: Promise<{ path?: string[] }> };
async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  const { path } = await ctx.params;
  const suffix = joinPath(path);
  return proxyToBackend(req, suffix ? `/api/staff/skip-trace/${suffix}` : "/api/staff/skip-trace", session.token);
}
export const GET = handle;
export const POST = handle;

/** One Digitap call bounded by the backend's 90s skip-trace read timeout; this is a ceiling, not a delay. */
export const maxDuration = 120;
