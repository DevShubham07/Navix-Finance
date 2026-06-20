import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * Name of the cookie that carries the staff session.
 * TODO: align this name with whatever the auth/login flow actually sets.
 */
const SESSION_COOKIE = "navix_session";

/**
 * Middleware that gates the internal (staff) routes.
 *
 * TODO:
 *  - Verify/decode the session token (signed with AUTH_SECRET) instead of just
 *    checking for cookie presence.
 *  - Extract the staff role (CREDIT_EXECUTIVE, CREDIT_HEAD, DISBURSEMENT_HEAD,
 *    ACCOUNTANT) and enforce per-route authorization (maker-checker SoD).
 *  - Redirect unauthorized-but-authenticated users to a "not allowed" page.
 */
export function middleware(request: NextRequest) {
  const session = request.cookies.get(SESSION_COOKIE)?.value;

  // TODO: replace presence check with real token verification.
  if (!session) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", request.nextUrl.pathname);
    return NextResponse.redirect(loginUrl);
  }

  // TODO: role-based authorization for the requested staff route.
  return NextResponse.next();
}

/**
 * Only run the middleware for internal staff areas.
 * TODO: extend the matcher as staff route segments are added.
 */
export const config = {
  matcher: [
    "/review/:path*",
    "/approve/:path*",
    "/disburse/:path*",
    "/accounting/:path*",
    "/staff/:path*",
  ],
};
