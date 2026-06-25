import { NextResponse } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";

/** Current borrower session (or null). Used by the live apply page. */
export async function GET() {
  const session = await getBorrowerSession();
  return NextResponse.json({ session });
}
