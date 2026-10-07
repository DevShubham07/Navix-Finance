import { NextResponse } from "next/server";
import { config } from "@/lib/config";

/**
 * Anonymous "is new onboarding paused?" probe. Proxies the backend `GET /api/auth/borrower/onboarding`
 * (ApiResponse envelope) and returns the bare `{ paused }`. Fails open (`paused: false`) if the backend
 * is unreachable so sign-in is never blocked by this check.
 */
export async function GET() {
  try {
    const res = await fetch(`${config.backendBaseUrl}/api/auth/borrower/onboarding`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!res.ok) return NextResponse.json({ paused: false });
    const data = (await res.json()) as { data?: { paused?: boolean } };
    return NextResponse.json({ paused: !!data.data?.paused });
  } catch {
    return NextResponse.json({ paused: false });
  }
}
