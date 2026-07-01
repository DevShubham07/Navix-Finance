import { NextResponse, type NextRequest } from "next/server";
import { setStaffSession } from "@/lib/api/bff-session";
import type { StaffRole } from "@/lib/auth/rbac";
import { config } from "@/lib/config";

/**
 * Staff invite activation. PUBLIC — an invitee has no session yet; the one-time token is the
 * credential. POST `{ token, name, password, mobile? }` -> backend
 * `POST /api/auth/staff/accept-invite`, which sets the password and returns a staff JWT. On success
 * the token is stored in the httpOnly `navix_staff` cookie (so the invitee lands signed in); the
 * response body omits the token. Mirrors the staff-login route.
 */

interface StaffAuthData {
  token: string;
  id: string | number;
  name: string;
  role: StaffRole;
  customerId: number | null;
}

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const { token, name, password, mobile } = (body ?? {}) as {
    token?: unknown;
    name?: unknown;
    password?: unknown;
    mobile?: unknown;
  };

  if (typeof token !== "string" || !token.trim()
      || typeof name !== "string" || !name.trim()
      || typeof password !== "string" || !password) {
    return NextResponse.json({ error: "Provide your invite token, name, and a password." }, { status: 400 });
  }

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/staff/accept-invite`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        token: token.trim(),
        name: name.trim(),
        password,
        mobile: typeof mobile === "string" ? mobile.trim() : undefined,
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }

  const text = await backendRes.text();
  // On failure, pass the backend envelope (with error.code) straight through.
  if (!backendRes.ok) {
    // Propagate the backend's correlation id so the UI error can show a ref that greps to logs.
    const headers: Record<string, string> = {
      "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
    };
    const rid = backendRes.headers.get("X-Request-Id");
    if (rid) headers["X-Request-Id"] = rid;
    return new NextResponse(text, { status: backendRes.status, headers });
  }

  let data: StaffAuthData;
  try {
    data = (JSON.parse(text) as { data: StaffAuthData }).data;
  } catch {
    return NextResponse.json({ error: "Unexpected activation response." }, { status: 502 });
  }

  const session = { token: data.token, id: String(data.id), name: data.name, role: data.role };
  await setStaffSession(session);
  // Never return the token to the browser.
  return NextResponse.json({ id: session.id, name: session.name, role: session.role });
}
