import { type NextRequest } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized, forbidden } from "@/lib/api/bff-proxy";

/**
 * Borrower loan proxy. Catch-all ->
 *   `${backendBaseUrl}/api/loan/${path}${search}`
 * injecting BORROWER identity from the `navix_borrower` cookie.
 *
 *  - GET  : loan summary / outstanding / repayment history (read-only).
 *  - POST : record a repayment (`{loanId}/repayments`) only. Verifying a payment
 *           is an accountant action and is rejected here.
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

export async function POST(req: NextRequest, ctx: Ctx) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  // Borrowers may only record a repayment — never verify one.
  if (!/^\d+\/repayments$/.test(suffix)) {
    return forbidden("Only recording a repayment is allowed here.");
  }

  return proxyToBackend(req, `/api/loan/${suffix}`, {
    id: session.applicantId,
    name: session.name,
    role: "BORROWER",
  });
}
