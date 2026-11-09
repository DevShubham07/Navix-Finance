import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Bank Statement Analysis proxy — `GET`/`POST /api/staff/bank-analysis/customers/{id}`. Who may run
 * (the credit desk + ADMIN) versus read (any staff but DSA) is the backend's decision, in
 * `BankStatementAnalysisService`.
 */
type Ctx = { params: Promise<{ path?: string[] }> };
async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  const { path } = await ctx.params;
  const suffix = joinPath(path);
  return proxyToBackend(
    req,
    suffix ? `/api/staff/bank-analysis/${suffix}` : "/api/staff/bank-analysis",
    session.token,
  );
}
export const GET = handle;
export const POST = handle;

/** A submission uploads several statements to Signzy; the analysis itself happens afterwards. */
export const maxDuration = 120;
