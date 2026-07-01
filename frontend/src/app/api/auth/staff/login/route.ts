import { NextResponse, type NextRequest } from "next/server";
import { setStaffSession } from "@/lib/api/bff-session";
import {
  STAFF_PERSONA_EMAILS,
  STAFF_DEFAULT_PASSWORD,
  isStaffRole,
} from "@/lib/api/staff-personas";
import type { StaffRole } from "@/lib/auth/rbac";
import { config } from "@/lib/config";

/**
 * Staff login. SEPARATE from borrower auth.
 *
 * Keeps the "pick a role" UX: POST `{ role }` maps the chosen role to its seeded
 * staff account (email + the shared demo password) and authenticates against the
 * backend `POST /api/auth/staff/login`. An explicit `{ email, password }` is also
 * accepted. On success the backend JWT is stored in the httpOnly `navix_staff`
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

  const { role, email, password } = (body ?? {}) as {
    role?: unknown;
    email?: unknown;
    password?: unknown;
  };

  // Resolve credentials: an explicit email+password, else the role -> seeded account map.
  let loginEmail: string;
  let loginPassword: string;
  if (typeof email === "string" && email && typeof password === "string" && password) {
    loginEmail = email;
    loginPassword = password;
  } else if (isStaffRole(role)) {
    loginEmail = STAFF_PERSONA_EMAILS[role];
    loginPassword = STAFF_DEFAULT_PASSWORD;
  } else {
    return NextResponse.json({ error: "Provide a staff role or email + password." }, { status: 400 });
  }

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/staff/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ email: loginEmail, password: loginPassword }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }

  const text = await backendRes.text();
  // On failure, pass the backend envelope (with error.code) straight through.
  if (!backendRes.ok) {
    return new NextResponse(text, {
      status: backendRes.status,
      headers: { "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json" },
    });
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
