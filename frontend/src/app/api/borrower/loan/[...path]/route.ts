import { type NextRequest } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Borrower loan proxy (read-only). Catch-all GET ->
 *   `${backendBaseUrl}/api/loan/${path}${search}`
 * injecting BORROWER identity from the `navix_borrower` cookie, so the live
 * apply page can render the loan summary (net disbursed, due date, total
 * repayable) once the application reaches ACTIVE.
 */

type Ctx = { params: Promise<{ path?: string[] }> };

export async function GET(req: NextRequest, ctx: Ctx) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/loan/${suffix}` : "/api/loan";

  return proxyToBackend(req, backendPath, {
    id: session.applicantId,
    name: session.name,
    role: "BORROWER",
  });
}
