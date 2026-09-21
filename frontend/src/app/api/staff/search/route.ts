import { NextRequest } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/**
 * Global-search proxy for the staff console's Cmd/Ctrl+K palette.
 *
 * <p>A plain (non catch-all) route: the palette hits exactly one path, and a static segment wins
 * over any future `[[...path]]` sibling in the App Router. The query string rides along via
 * `proxyToBackend`, and every RBAC decision — which groups run, how each is scoped, and the outright
 * rejection of DSA — is the backend's.
 */
async function handle(req: NextRequest) {
  const session = await getStaffSession();
  if (!session) return unauthorized("Staff session required.");
  return proxyToBackend(req, "/api/staff/search", session.token);
}

export const GET = handle;
