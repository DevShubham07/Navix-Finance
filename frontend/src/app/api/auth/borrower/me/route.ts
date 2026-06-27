import { NextResponse } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";

/** Current borrower identity (token stripped), or null. Used by the live borrower app. */
export async function GET() {
  const session = await getBorrowerSession();
  return NextResponse.json({
    session: session
      ? { id: session.id, applicantId: session.applicantId, name: session.name, mobile: session.mobile }
      : null,
  });
}
