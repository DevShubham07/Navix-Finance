import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * Name of the cookie that carries the staff session — the httpOnly cookie the BFF login route
 * (`/api/auth/staff/login`) actually sets. (Was `navix_session`, which nothing set, so the gate
 * was a no-op.) Real token verification is still a TODO; for now we check presence of this cookie.
 */
const SESSION_COOKIE = "navix_staff";

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
/**
 * Staff routes that must stay reachable without a session, otherwise the
 * gate below would redirect the login/activation screens onto themselves.
 */
const PUBLIC_STAFF_PATHS = ["/staff/login", "/staff/activate"];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_STAFF_PATHS.some((path) => pathname.startsWith(path))) {
    return NextResponse.next();
  }

  const session = request.cookies.get(SESSION_COOKIE)?.value;

  // TODO: replace presence check with real token verification.
  if (!session) {
    const loginUrl = new URL("/staff/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }

  // TODO: role-based authorization for the requested staff route.
  return NextResponse.next();
}

/**
 * Only run the middleware for internal staff areas. All staff screens live
 * under the /staff segment, so a single matcher covers the whole console.
 */
export const config = {
  matcher: ["/staff/:path*"],
};
