import { NextResponse } from "next/server";
import { getStaffSession } from "@/lib/api/bff-session";

/** Current staff session (or null). Used by the live staff console. */
export async function GET() {
  const session = await getStaffSession();
  return NextResponse.json({ session });
}
