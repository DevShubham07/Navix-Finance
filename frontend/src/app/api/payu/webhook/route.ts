import { type NextRequest, NextResponse } from "next/server";
import { config } from "@/lib/config";

/** PayU server-to-server webhook: forward the raw body (the backend verifies the hash); always 200. */
export async function POST(req: NextRequest) {
  try {
    await fetch(`${config.backendBaseUrl}/api/webhooks/payu/payment`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: await req.text(),
      cache: "no-store",
    });
  } catch {
    /* the backend's reconcile sweep catches up */
  }
  return new NextResponse(null, { status: 200 });
}
