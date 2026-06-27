import { type NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, joinPath, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Storage proxy (presigned URL issuance). Catch-all ->
 *   `${backendBaseUrl}/api/storage/${path}${search}`
 * injecting STAFF identity from the `navix_staff` cookie. 401 if no session.
 *
 *  - POST presign-upload : admin requests a presigned PUT URL for a QR / account-info asset.
 *  - GET  presign-download : presigned GET URL for an existing key.
 *
 * The actual bytes go browser <-> S3 directly via the returned presigned URL (never through here).
 */

type Ctx = { params: Promise<{ path?: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");

  const { path } = await ctx.params;
  const suffix = joinPath(path);
  const backendPath = suffix ? `/api/storage/${suffix}` : "/api/storage";

  return proxyToBackend(req, backendPath, {
    id: session.id,
    name: session.name,
    role: session.role,
  });
}

export const GET = handle;
export const POST = handle;
