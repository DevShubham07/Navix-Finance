import { NextResponse, type NextRequest } from "next/server";

/**
 * BFF auth route. Thin proxy to the Spring backend (BACKEND_BASE_URL).
 * Handles staff login / session issuance (signed with AUTH_SECRET server-side).
 */

export async function GET(_req: NextRequest) {
  // TODO: return the current session from the signed cookie.
  return NextResponse.json({ session: null, todo: "auth GET not implemented" });
}

export async function POST(_req: NextRequest) {
  // TODO: proxy login to backend /iam/login and set the session cookie.
  return NextResponse.json({ todo: "auth POST not implemented" }, { status: 501 });
}
