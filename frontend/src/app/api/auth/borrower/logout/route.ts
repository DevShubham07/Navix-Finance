import { NextResponse } from "next/server";
import { clearBorrowerSession } from "@/lib/api/bff-session";

/** Clear the borrower session cookie. */
export async function POST() {
  await clearBorrowerSession();
  return NextResponse.json({ ok: true });
}
