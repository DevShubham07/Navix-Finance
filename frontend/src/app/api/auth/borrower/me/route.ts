import { NextResponse } from "next/server";
import { getBorrowerSession, setBorrowerSession } from "@/lib/api/bff-session";

/** Current borrower identity (token stripped), or null. Used by the live borrower app. */
export async function GET() {
  const session = await getBorrowerSession();
  return NextResponse.json({
    session: session
      ? { id: session.id, applicantId: session.applicantId, name: session.name, mobile: session.mobile }
      : null,
  });
}

/**
 * Update the display name on the live borrower session. Called when the borrower
 * types their name during onboarding so the header/dashboard greet them by name
 * (the login-issued session defaults to "Borrower" until a name exists). Only the
 * cookie's display name changes — the JWT and identity are untouched.
 */
export async function POST(req: Request) {
  const session = await getBorrowerSession();
  if (!session) return NextResponse.json({ session: null }, { status: 401 });
  const body = (await req.json().catch(() => ({}))) as { name?: unknown };
  const name = typeof body.name === "string" ? body.name.trim() : "";
  if (name) await setBorrowerSession({ ...session, name });
  const next = name ? { ...session, name } : session;
  return NextResponse.json({
    session: { id: next.id, applicantId: next.applicantId, name: next.name, mobile: next.mobile },
  });
}
