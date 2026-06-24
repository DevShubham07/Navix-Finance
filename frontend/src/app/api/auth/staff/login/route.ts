import { NextResponse, type NextRequest } from "next/server";
import { setStaffSession } from "@/lib/api/bff-session";
import { STAFF_PERSONA_NAMES, isStaffRole } from "@/lib/api/staff-personas";

/**
 * Staff login (demo — no password). POST `{ role }` -> sets the httpOnly
 * `navix_staff` cookie and returns the session. SEPARATE from borrower auth.
 */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const role = (body as { role?: unknown } | null)?.role;
  if (!isStaffRole(role)) {
    return NextResponse.json({ error: "Unknown or missing staff role." }, { status: 400 });
  }

  const session = {
    id: `staff-${role}`,
    name: STAFF_PERSONA_NAMES[role],
    role,
  };

  await setStaffSession(session);
  return NextResponse.json(session);
}
