import { NextResponse } from "next/server";
import { clearStaffSession } from "@/lib/api/bff-session";

/** Clear the staff session cookie. */
export async function POST() {
  await clearStaffSession();
  return NextResponse.json({ ok: true });
}
