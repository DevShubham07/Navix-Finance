import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * The httpOnly cookie the BFF staff login (`/api/auth/staff/login`) sets — a JSON blob
 * `{ token, id, name, role }` where `token` is the backend-issued JWT.
 *
 * The authoritative authorization boundary is the **backend** (`JwtAuthFilter` validates the bearer
 * on every `/api/**` call, and services enforce roles). This middleware is the UX gate for the staff
 * shell: it now rejects a missing, malformed, OR expired session (not just presence) and redirects to
 * login, clearing the stale cookie. (Full JWT signature verification at the edge would additionally
 * need `AUTH_SECRET` available to the middleware runtime — a follow-up; the backend already verifies it.)
 */
const SESSION_COOKIE = "navix_staff";
const PUBLIC_STAFF_PATHS = ["/staff/login", "/staff/activate", "/staff/forgot-password", "/staff/reset-password"];

/** Decode a JWT's `exp` (seconds since epoch) WITHOUT verifying the signature. Null if unparseable. */
function jwtExp(token: string): number | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const json = atob(parts[1].replace(/-/g, "+").replace(/_/g, "/"));
    const payload = JSON.parse(json) as { exp?: number };
    return typeof payload.exp === "number" ? payload.exp : null;
  } catch {
    return null;
  }
}

/** A staff session is usable if the cookie holds a well-formed, unexpired bearer token. */
function hasValidStaffSession(raw: string | undefined): boolean {
  if (!raw) return false;
  try {
    const session = JSON.parse(raw) as { token?: string };
    if (!session.token) return false;
    const exp = jwtExp(session.token);
    // Reject an expired token; if exp can't be read, fall back to presence (backend still verifies).
    return exp == null || exp * 1000 > Date.now();
  } catch {
    return false;
  }
}

/** Gate the internal (staff) routes — all staff screens live under `/staff`. */
export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_STAFF_PATHS.some((path) => pathname.startsWith(path))) {
    return NextResponse.next();
  }

  if (!hasValidStaffSession(request.cookies.get(SESSION_COOKIE)?.value)) {
    const loginUrl = new URL("/staff/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    const res = NextResponse.redirect(loginUrl);
    res.cookies.delete(SESSION_COOKIE); // drop a malformed / expired session cookie
    return res;
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/staff/:path*"],
};
