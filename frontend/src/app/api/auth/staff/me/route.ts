import { NextResponse } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";

/** Current staff identity (token stripped), or null. Used by the live staff console. */
export async function GET() {
  const session = await getStaffSession();
  return NextResponse.json({
    session: session ? { id: session.id, name: session.name, role: session.role } : null,
  });
}
