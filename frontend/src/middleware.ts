import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { CLEAN_COOKIE, CLEAN_TTL_SECONDS, evaluateRequest, readGuardConfig } from "@/lib/network-guard";

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

const BLOCKED_PAGE = "/network-blocked";
const BLOCKED_MESSAGE =
  "DhanBoost is available only from India, without a VPN, proxy or private relay. Please switch it off and try again.";

/**
 * Site-wide network guard (see `lib/network-guard.ts`): VPN / proxy / Tor / non-India visitors get
 * the block page (pages) or a 403 envelope (BFF `/api/*`). Returns the response to send, or null to
 * carry on; a fresh clean verdict comes back as `cleanToken` for the caller to set as a cookie.
 */
async function networkGuard(request: NextRequest): Promise<{ blocked: NextResponse | null; cleanToken?: string }> {
  const outcome = await evaluateRequest(request.headers, request.cookies.get(CLEAN_COOKIE)?.value, readGuardConfig());
  const v = outcome.verdict;
  if (!v.blocked) return { blocked: null, cleanToken: outcome.newCleanToken };

  console.warn(`[network-guard] blocked ${v.reason}${v.detail ? ` (${v.detail})` : ""} ${request.nextUrl.pathname}`);
  if (request.nextUrl.pathname.startsWith("/api/")) {
    return {
      blocked: NextResponse.json(
        {
          success: false,
          message: BLOCKED_MESSAGE,
          data: null,
          error: { code: "NETWORK_BLOCKED", message: BLOCKED_MESSAGE, reason: v.reason },
          timestamp: new Date().toISOString(),
        },
        { status: 403 },
      ),
    };
  }
  const url = request.nextUrl.clone();
  url.pathname = BLOCKED_PAGE;
  url.search = `?reason=${v.reason}`;
  return { blocked: NextResponse.rewrite(url, { status: 403 }) };
}

export async function middleware(request: NextRequest) {
  const guard = await networkGuard(request);
  if (guard.blocked) return guard.blocked;
  const res = staffGate(request);
  if (guard.cleanToken) {
    res.cookies.set(CLEAN_COOKIE, guard.cleanToken, {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      path: "/",
      maxAge: CLEAN_TTL_SECONDS,
    });
  }
  return res;
}

/** Gate the internal (staff) routes — all staff screens live under `/staff`. */
function staffGate(request: NextRequest): NextResponse {
  const { pathname } = request.nextUrl;
  if (!pathname.startsWith("/staff")) return NextResponse.next();

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
  /*
   * Everything except: Next's static/image assets, any file with an extension (robots.txt,
   * sitemap.xml, images, verification files), the generated icons/social cards, the block page
   * itself, and PayU's server-to-server webhook + browser return — a payment outcome must never be
   * dropped because of where the request came from.
   */
  matcher: [
    "/((?!_next/static|_next/image|api/payu/|network-blocked|opengraph-image|twitter-image|icon|apple-icon|.*\\.[A-Za-z0-9]+$).*)",
  ],
};
