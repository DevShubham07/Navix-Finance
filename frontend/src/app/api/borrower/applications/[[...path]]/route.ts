import { type NextRequest } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Borrower applications proxy. Catch-all GET/POST ->
 *   `${backendBaseUrl}/api/applications/${path}${search}`
 * injecting BORROWER identity (actor id = applicantId) from the
 * `navix_borrower` cookie. 401 if no session.
 *
 * SEPARATE from the staff proxy: only the borrower cookie is honoured here.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/applications/${suffix}` : "/api/applications";

  return proxyToBackend(req, backendPath, {
    id: session.applicantId, // backend actor id == applicantId for borrowers
    name: session.name,
    role: "BORROWER",
  });
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
