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

  return proxyToBackend(req, backendPath, session.token);
}

export async function POST(req: NextRequest, ctx: Ctx) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  // Borrowers may only record a repayment or start a PayU checkout — never verify a payment.
  if (!/^\d+\/(repayments|payu\/orders)$/.test(suffix)) {
    return forbidden("Only recording a repayment or starting a PayU payment is allowed here.");
  }

  return proxyToBackend(req, `/api/loan/${suffix}`, session.token);
}
