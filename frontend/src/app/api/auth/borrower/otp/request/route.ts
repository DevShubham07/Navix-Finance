import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/**
 * Request a borrower OTP. Proxies `POST { mobile }` to the backend
 * `POST /api/auth/borrower/otp/request`, which generates a code and delivers it via
 * the UltronSMS gateway. Pre-login — no session. Returns `{ sent, ttlSeconds, devCode }`
 * (`devCode` is present only when the backend runs with `navix.sms.dev-echo=true`).
 */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const { mobile } = (body ?? {}) as { mobile?: unknown };
  const cleanMobile = typeof mobile === "string" ? mobile.replace(/\D/g, "") : "";
  if (cleanMobile.length < 10) {
    return NextResponse.json({ error: "Enter a valid 10-digit mobile number." }, { status: 400 });
  }

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/borrower/otp/request`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ mobile: cleanMobile }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the OTP service." }, { status: 502 });
  }

  const text = await backendRes.text();
  if (!backendRes.ok) {
    return new NextResponse(text, {
      status: backendRes.status,
      headers: { "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json" },
    });
  }

  try {
    const data = (JSON.parse(text) as { data: { sent: boolean; ttlSeconds: number; devCode?: string } }).data;
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ error: "Unexpected OTP response." }, { status: 502 });
  }
}
