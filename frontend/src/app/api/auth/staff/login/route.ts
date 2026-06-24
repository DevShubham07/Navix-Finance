import { NextResponse, type NextRequest } from "next/server";
import { setStaffSession } from "@/lib/api/bff-session";
import { STAFF_PERSONA_NAMES, isStaffRole } from "@/lib/api/staff-personas";
import { config } from "@/lib/config";

/**
 * Staff login (demo — no password). POST `{ role }` -> sets the httpOnly
 * `navix_staff` cookie and returns the session. SEPARATE from borrower auth.
 *
 * The session id is bound to a REAL seeded staff member of that role so the
 * backend sees a real `staff_user.id` (a bigint). That makes separation-of-duties
 * and the collections officer / settlement proposer-approver names real, not
 * synthetic. Falls back to a synthetic id if the backend can't be reached.
 */

interface BackendStaff {
  id: number;
  name: string;
  role: string;
  status: string;
}

/** Resolve a role to its first ACTIVE seeded staff member (lowest id). */
async function resolveRealStaff(role: string): Promise<{ id: string; name: string } | null> {
  try {
    const res = await fetch(`${config.backendBaseUrl}/api/staff`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!res.ok) return null;
    const env = (await res.json()) as { data?: BackendStaff[] };
    const match = (env.data ?? [])
      .filter((s) => s.role === role && s.status === "ACTIVE")
      .sort((a, b) => a.id - b.id)[0];
    return match ? { id: String(match.id), name: match.name } : null;
  } catch {
    return null;
  }
}

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

  const real = await resolveRealStaff(role);
  const session = real
    ? { id: real.id, name: real.name, role }
    : { id: `staff-${role}`, name: STAFF_PERSONA_NAMES[role], role };

  await setStaffSession(session);
  return NextResponse.json(session);
}
