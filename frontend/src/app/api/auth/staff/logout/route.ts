import { NextResponse } from "next/server";
import { clearStaffSession, getStaffSession } from "@/lib/api/bff-session";
import { config } from "@/lib/config";

/**
 * Clear the staff session cookie — and tell the backend first, so `staff_user.active_session_id`
 * is cleared too. Without this a clean sign-out left the session "live" server-side, forcing the
 * staffer's NEXT login to pass `force` even though nothing was actually still signed in.
 */
export async function POST() {
  const session = await getStaffSession();
  if (session) {
    try {
      await fetch(`${config.backendBaseUrl}/api/auth/staff/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${session.token}`, Accept: "application/json" },
        cache: "no-store",
      });
    } catch {
      // Best-effort: the cookie still gets cleared below either way, and the session's TTL/next
      // request will settle it (e.g. a forced login elsewhere clears it too).
    }
  }
  await clearStaffSession();
  return NextResponse.json({ ok: true });
}
