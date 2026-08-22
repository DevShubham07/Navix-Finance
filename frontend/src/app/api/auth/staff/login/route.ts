import { NextResponse, type NextRequest } from "next/server";
import { setStaffSession } from "@/lib/api/bff-session";
import type { StaffRole } from "@/lib/auth/rbac";
import { config } from "@/lib/config";

/**
 * Staff login. SEPARATE from borrower auth.
 *
 * POST `{ email, password }` authenticates against the backend
 * `POST /api/auth/staff/login`. There is deliberately NO role shortcut — a staff
 * member's role comes from their own account, and switching roles means signing
 * out and signing in as that account. On success the backend JWT is stored in the httpOnly `navix_staff`
 * cookie (`{ token, id, name, role }`); the response body omits the token.
 */

interface StaffLoginData {
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

  const { email, password, force, captchaToken } = (body ?? {}) as {
    email?: unknown;
    password?: unknown;
    force?: unknown;
    captchaToken?: unknown;
  };

  if (typeof email !== "string" || !email || typeof password !== "string" || !password) {
    return NextResponse.json({ error: "Provide email + password." }, { status: 400 });
  }
  const loginEmail = email;
  const loginPassword = password;

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/staff/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        email: loginEmail,
        password: loginPassword,
        force: force === true,
        // Forwarded verbatim — only the backend may judge it.
        captchaToken: typeof captchaToken === "string" ? captchaToken : "",
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }

  const text = await backendRes.text();
  // On failure, pass the backend envelope (with error.code) straight through.
  if (!backendRes.ok) {
    // Propagate the backend's correlation id so the sign-in error can show a ref that greps to logs.
    const headers: Record<string, string> = {
      "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
    };
    const rid = backendRes.headers.get("X-Request-Id");
    if (rid) headers["X-Request-Id"] = rid;
    return new NextResponse(text, { status: backendRes.status, headers });
  }

  let data: StaffLoginData;
  try {
    data = (JSON.parse(text) as { data: StaffLoginData }).data;
  } catch {
    return NextResponse.json({ error: "Unexpected authentication response." }, { status: 502 });
  }

  const session = { token: data.token, id: String(data.id), name: data.name, role: data.role };
  await setStaffSession(session);
  // Never return the token to the browser.
  return NextResponse.json({ id: session.id, name: session.name, role: session.role });
}
